package dev.devfield.repoq.project.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.devfield.repoq.project.api.ApiProblem;
import dev.devfield.repoq.project.github.GitHubRepositoryClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class Projects {
    public record Member(UUID id, String githubRepositoryId, String fullName, String visibility,
                         String accessStatus, String analysisStatus, String commitSha) {}
    public record Item(UUID id, String name, String analysisStatus, int repositoryCount,
                       int privateRepositoryCount, boolean canInterview) {}
    public record Detail(UUID id, String name, String projectStatus, String analysisStatus,
                         boolean canInterview, Object latestReadySnapshot, long membershipVersion,
                         List<Member> repositories, boolean canPrivateInterview) {}
    public record Registration(UUID id, String analysisStatus, List<Member> repositories) {}
    public record Addition(UUID projectId, long membershipVersion, String analysisStatus,
                           List<Member> repositories) {}

    private final JdbcTemplate db;
    private final ObjectMapper json = new ObjectMapper();

    public Projects(JdbcTemplate db) { this.db = db; }

    public Registration replayRegistration(UUID owner, String key, String hash) {
        var prior = db.query("SELECT p.create_request_hash,j.response_json::text,p.state,uf.state " +
                        "FROM project.registered_project p " +
                        "JOIN project.analysis_job j ON j.project_id=p.id AND j.trigger_kind='INITIAL' " +
                        "JOIN project.project_write_fence uf ON uf.scope='USER' AND uf.owner_user_id=p.owner_user_id " +
                        "WHERE p.owner_user_id=? AND p.create_request_key=? ORDER BY j.created_at LIMIT 1",
                (rs, row) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)},
                owner, key);
        if (prior.isEmpty()) return null;
        if (!"ACTIVE".equals(prior.getFirst()[3]))
            throw new ApiProblem(HttpStatus.FORBIDDEN, "account_unavailable");
        if (!"ACTIVE".equals(prior.getFirst()[2])) throw conflict("project_unavailable");
        if (!hash.equals(prior.getFirst()[0])) throw conflict("idempotency_key_conflict");
        return fromJson(prior.getFirst()[1], Registration.class);
    }

    public Addition replayAddition(UUID owner, UUID projectId, String key, String hash) {
        var prior = db.query("SELECT j.request_hash,j.response_json::text,p.state,uf.state,pf.state " +
                        "FROM project.analysis_job j " +
                        "JOIN project.registered_project p ON p.id=j.project_id " +
                        "JOIN project.project_write_fence uf ON uf.scope='USER' AND uf.owner_user_id=p.owner_user_id " +
                        "JOIN project.project_write_fence pf ON pf.scope='PROJECT' AND pf.project_id=p.id " +
                        "WHERE p.owner_user_id=? AND j.project_id=? AND j.request_scope='ADD' AND j.request_key=?",
                (rs, row) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)}, owner, projectId, key);
        if (prior.isEmpty()) return null;
        if (!"ACTIVE".equals(prior.getFirst()[3]))
            throw new ApiProblem(HttpStatus.FORBIDDEN, "account_unavailable");
        if (!"ACTIVE".equals(prior.getFirst()[2]) || !"ACTIVE".equals(prior.getFirst()[4]))
            throw conflict("project_unavailable");
        if (!hash.equals(prior.getFirst()[0])) throw conflict("idempotency_key_conflict");
        return fromJson(prior.getFirst()[1], Addition.class);
    }

    @Transactional
    public Registration register(UUID owner, GitHubRepositoryClient.Repository repository, String name) {
        return register(owner, List.of(repository), name, null, null);
    }

    @Transactional
    public Registration register(UUID owner, List<GitHubRepositoryClient.Repository> repositories,
                                 String name, String requestKey, String requestHash) {
        requireRepositories(repositories);
        lockUser(owner);
        if (requestKey != null) {
            Registration replay = replayRegistration(owner, requestKey, requestHash);
            if (replay != null) return replay;
        }
        UUID projectId = UUID.randomUUID();
        var first = repositories.getFirst();
        try {
            db.update("INSERT INTO project.registered_project" +
                            "(id,owner_user_id,github_repository_id,repository_full_name,display_name," +
                            "create_request_key,create_request_hash) VALUES (?,?,?,?,?,?,?)",
                    projectId, owner, first.id(), first.fullName(), name, requestKey, requestHash);
            db.update("INSERT INTO project.project_write_fence(id,scope,owner_user_id,project_id,state) " +
                            "VALUES (?,'PROJECT',?,?,'ACTIVE')", UUID.randomUUID(), owner, projectId);
            insertMembers(projectId, owner, repositories);
            var members = membersForQueued(projectId);
            Registration response = new Registration(projectId, "QUEUED", members);
            insertJob(projectId, 1, "INITIAL", members, "CREATE", requestKey, requestHash, response);
            return response;
        } catch (DuplicateKeyException e) {
            throw conflict("repository_already_registered");
        }
    }

    @Transactional
    public Addition add(UUID owner, UUID projectId, List<GitHubRepositoryClient.Repository> repositories,
                        String requestKey, String requestHash) {
        requireRepositories(repositories);
        lockUser(owner);
        long version = lockProject(owner, projectId);
        if (requestKey != null) {
            Addition replay = replayAddition(owner, projectId, requestKey, requestHash);
            if (replay != null) return replay;
        }
        if (hasRunningJob(projectId)) throw conflict("analysis_in_progress");
        // A-stage QUEUED jobs have no worker. Replace one atomically so adding to a new project works.
        cancelQueuedJob(projectId);
        try {
            insertMembers(projectId, owner, repositories);
        } catch (DuplicateKeyException e) {
            throw conflict("repository_already_registered");
        }
        long nextVersion = version + 1;
        db.update("UPDATE project.registered_project SET membership_version=?,updated_at=now() WHERE id=?",
                nextVersion, projectId);
        var members = membersForQueued(projectId);
        Addition response = new Addition(projectId, nextVersion, "QUEUED", members);
        insertJob(projectId, nextVersion, "MEMBERSHIP_CHANGED", members, "ADD", requestKey, requestHash, response);
        return response;
    }

    @Transactional
    public void remove(UUID owner, UUID projectId, UUID memberId) {
        lockUser(owner);
        long version = lockProject(owner, projectId);
        var rows = db.query("SELECT removed_at IS NOT NULL FROM project.project_repository " +
                        "WHERE id=? AND project_id=? AND owner_user_id=? FOR UPDATE",
                (rs, row) -> rs.getBoolean(1), memberId, projectId, owner);
        if (rows.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "repository_not_found");
        if (rows.getFirst()) return;
        int count = db.queryForObject("SELECT count(*) FROM project.project_repository " +
                "WHERE project_id=? AND removed_at IS NULL", Integer.class, projectId);
        if (count <= 1) throw conflict("last_repository_required");
        // A-stage has no worker cancellation handshake; refuse removal while one is running.
        if (hasRunningJob(projectId)) throw conflict("analysis_in_progress");
        cancelQueuedJob(projectId);
        db.update("UPDATE project.project_repository SET removed_at=now() WHERE id=?", memberId);
        long nextVersion = version + 1;
        db.update("UPDATE project.registered_project SET membership_version=?,updated_at=now() WHERE id=?",
                nextVersion, projectId);
        insertJob(projectId, nextVersion, "MEMBERSHIP_CHANGED", membersForQueued(projectId),
                null, null, null, null);
    }

    public List<Item> list(UUID owner) {
        return db.query("SELECT p.id,p.display_name,j.state," +
                        "(SELECT count(*) FROM project.project_repository r WHERE r.project_id=p.id AND r.removed_at IS NULL)," +
                        "(SELECT count(*) FROM project.project_repository r WHERE r.project_id=p.id " +
                        "AND r.removed_at IS NULL AND r.visibility='PRIVATE') " +
                        "FROM project.registered_project p " +
                        "JOIN LATERAL (SELECT state FROM project.analysis_job WHERE project_id=p.id " +
                        "ORDER BY created_at DESC,id DESC LIMIT 1) j ON true " +
                        "WHERE p.owner_user_id=? ORDER BY p.created_at DESC,p.id DESC",
                (rs, row) -> new Item(rs.getObject(1, UUID.class), rs.getString(2), safeStatus(rs.getString(3)),
                        rs.getInt(4), rs.getInt(5), false), owner);
    }

    public Detail detail(UUID owner, UUID id) {
        var rows = db.query("SELECT p.id,p.display_name,p.state,p.membership_version,j.id,j.state " +
                        "FROM project.registered_project p " +
                        "JOIN LATERAL (SELECT id,state FROM project.analysis_job WHERE project_id=p.id " +
                        "ORDER BY created_at DESC,id DESC LIMIT 1) j ON true " +
                        "WHERE p.owner_user_id=? AND p.id=?",
                (rs, row) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getObject(5, UUID.class), rs.getString(6)}, owner, id);
        if (rows.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "project_not_found");
        Object[] row = rows.getFirst();
        UUID jobId = (UUID) row[4];
        var members = db.query("SELECT r.id,r.github_repository_id,r.repository_full_name,r.visibility,r.access_state," +
                        "COALESCE((SELECT x->>'state' FROM project.analysis_job j," +
                        "jsonb_array_elements(j.repository_progress) x " +
                        "WHERE j.id=? AND x->>'repositoryId'=r.id::text LIMIT 1),?)," +
                        "(SELECT x->>'commitSha' FROM project.analysis_job j," +
                        "jsonb_array_elements(j.repository_progress) x " +
                        "WHERE j.id=? AND x->>'repositoryId'=r.id::text LIMIT 1) " +
                        "FROM project.project_repository r WHERE r.project_id=? AND r.removed_at IS NULL " +
                        "ORDER BY r.added_at,r.id",
                (rs, index) -> new Member(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), safeStatus(rs.getString(6)), rs.getString(7)),
                jobId, row[5], jobId, id);
        return new Detail((UUID) row[0], (String) row[1], (String) row[2], safeStatus((String) row[5]),
                false, null, (Long) row[3], members, false);
    }

    public void requireOwned(UUID owner, UUID id) {
        var states = db.query("SELECT state FROM project.registered_project " +
                "WHERE id=? AND owner_user_id=?", (rs, row) -> rs.getString(1), id, owner);
        if (states.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "project_not_found");
        if (!"ACTIVE".equals(states.getFirst())) throw conflict("project_unavailable");
    }

    private void lockUser(UUID owner) {
        db.update("INSERT INTO project.project_write_fence(id,scope,owner_user_id,state) " +
                        "VALUES (?,'USER',?,'ACTIVE') ON CONFLICT (owner_user_id) WHERE scope='USER' DO NOTHING",
                UUID.randomUUID(), owner);
        String state = db.queryForObject("SELECT state FROM project.project_write_fence " +
                "WHERE scope='USER' AND owner_user_id=? FOR UPDATE", String.class, owner);
        if (!"ACTIVE".equals(state)) throw new ApiProblem(HttpStatus.FORBIDDEN, "account_unavailable");
    }

    private long lockProject(UUID owner, UUID id) {
        var fence = db.query("SELECT state FROM project.project_write_fence " +
                        "WHERE scope='PROJECT' AND owner_user_id=? AND project_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), owner, id);
        if (fence.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "project_not_found");
        if (!"ACTIVE".equals(fence.getFirst())) throw conflict("project_unavailable");
        var projects = db.query("SELECT membership_version,state FROM project.registered_project " +
                        "WHERE id=? AND owner_user_id=? FOR UPDATE",
                (rs, row) -> new Object[]{rs.getLong(1), rs.getString(2)}, id, owner);
        if (projects.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "project_not_found");
        if (!"ACTIVE".equals(projects.getFirst()[1])) throw conflict("project_unavailable");
        return (Long) projects.getFirst()[0];
    }

    private boolean hasRunningJob(UUID projectId) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS (SELECT 1 FROM project.analysis_job " +
                "WHERE project_id=? AND state='RUNNING')", Boolean.class, projectId));
    }

    private void cancelQueuedJob(UUID projectId) {
        db.update("UPDATE project.analysis_job SET state='CANCELLED',finished_at=now() " +
                "WHERE project_id=? AND state='QUEUED'", projectId);
    }

    private void insertMembers(UUID projectId, UUID owner,
                               List<GitHubRepositoryClient.Repository> repositories) {
        for (var repository : repositories) {
            db.update("INSERT INTO project.project_repository" +
                            "(id,project_id,owner_user_id,github_repository_id,repository_full_name,visibility) " +
                            "VALUES (?,?,?,?,?,'PUBLIC')",
                    UUID.randomUUID(), projectId, owner, repository.id(), repository.fullName());
        }
    }

    private List<Member> membersForQueued(UUID projectId) {
        return db.query("SELECT id,github_repository_id,repository_full_name,visibility,access_state " +
                        "FROM project.project_repository WHERE project_id=? AND removed_at IS NULL " +
                        "ORDER BY added_at,id",
                (rs, row) -> new Member(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), "QUEUED", null), projectId);
    }

    private void insertJob(UUID projectId, long version, String trigger, List<Member> members,
                           String scope, String key, String hash, Object response) {
        var progress = new ArrayList<java.util.Map<String, String>>();
        for (Member member : members)
            progress.add(java.util.Map.of("repositoryId", member.id().toString(), "state", "QUEUED"));
        db.update("INSERT INTO project.analysis_job" +
                        "(id,project_id,trigger_kind,state,analysis_version,membership_version," +
                        "repository_progress,request_scope,request_key,request_hash,response_json) " +
                        "VALUES (?,? ,?,'QUEUED','v1',?,CAST(? AS jsonb),?,?,?,CAST(? AS jsonb))",
                UUID.randomUUID(), projectId, trigger, version, toJson(progress), scope, key, hash,
                response == null ? null : toJson(response));
    }

    private static void requireRepositories(List<GitHubRepositoryClient.Repository> repositories) {
        if (repositories == null || repositories.isEmpty() || repositories.size() > 20)
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_repositories");
        var ids = new HashSet<String>();
        for (var repository : repositories) {
            if (repository == null || repository.id() == null || repository.id().isBlank() ||
                    repository.fullName() == null || repository.fullName().isBlank())
                throw new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_repositories");
            if (!ids.add(repository.id())) throw conflict("repository_already_registered");
        }
    }

    private String toJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private <T> T fromJson(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private static ApiProblem conflict(String code) { return new ApiProblem(HttpStatus.CONFLICT, code); }

    // A-stage has no published Snapshot. A legacy READY Job alone cannot make the project ready.
    private static String safeStatus(String state) { return "READY".equals(state) ? "FAILED" : state; }
}
