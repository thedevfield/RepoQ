package dev.devfield.repoq.project.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.devfield.repoq.project.api.ApiProblem;
import dev.devfield.repoq.project.github.GitHubRepositoryClient;
import dev.devfield.repoq.project.github.GitHubConnections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
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
    public record Revocation(UUID revocationId, String status) {}
    public record Disconnection(UUID connectionId, String status) {}
    public record Consent(String purpose, String decision, String policyVersion) {}
    public record ConsentStatus(String githubRepositoryId, String currentPolicyVersion,
                                boolean consentSubmissionAvailable, List<Consent> consents) {}
    public record Repository(GitHubRepositoryClient.Repository identity, UUID connectionId, List<Consent> consents) {}

    private final JdbcTemplate db;
    private final GitHubConnections connections;
    private final String policyVersion;
    private final ObjectMapper json = new ObjectMapper();

    public Projects(JdbcTemplate db, GitHubConnections connections,
                    @Value("${devfield.project.github.private-consent-policy-version:}") String policyVersion) {
        this.db = db; this.connections = connections; this.policyVersion = policyVersion;
    }

    public ConsentStatus consentStatus(UUID owner, String repositoryId) {
        if (repositoryId == null || !repositoryId.matches("[1-9][0-9]{0,18}"))
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_request");
        var latest = db.query("SELECT DISTINCT ON (purpose) purpose,decision,policy_version " +
                        "FROM project.repository_consent WHERE owner_user_id=? AND github_repository_id=? " +
                        "ORDER BY purpose,event_order DESC",
                (rs, n) -> new Consent(rs.getString(1), rs.getString(2), rs.getString(3)), owner, repositoryId);
        var consents = new ArrayList<Consent>(2);
        for (String purpose : List.of("PRIVATE_COLLECTION", "EXTERNAL_AI")) {
            consents.add(latest.stream().filter(c -> purpose.equals(c.purpose())).findFirst()
                    .orElse(new Consent(purpose, null, null)));
        }
        if (latest.isEmpty()) {
            try { connections.requirePrivateRepository(owner, repositoryId); }
            catch (ApiProblem e) {
                if (e.status() == HttpStatus.FORBIDDEN || e.status() == HttpStatus.UNAUTHORIZED ||
                        e.status() == HttpStatus.NOT_FOUND)
                    throw new ApiProblem(HttpStatus.NOT_FOUND, "repository_not_found");
                throw e;
            }
        }
        return new ConsentStatus(repositoryId, policyVersion.isBlank() ? null : policyVersion,
                !policyVersion.isBlank(), consents);
    }

    @Transactional
    public Registration replayRegistration(UUID owner, String key, String hash) {
        lockUser(owner);
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
        Registration replay = fromJson(prior.getFirst()[1], Registration.class);
        return new Registration(replay.id(), replay.repositories().stream().anyMatch(m -> "PRIVATE".equals(m.visibility()))
                ? latestStatus(replay.id()) : replay.analysisStatus(), redact(owner, replay.repositories()));
    }

    @Transactional
    public Addition replayAddition(UUID owner, UUID projectId, String key, String hash) {
        lockUser(owner);
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
        Addition replay = fromJson(prior.getFirst()[1], Addition.class);
        return new Addition(replay.projectId(), replay.membershipVersion(),
                replay.repositories().stream().anyMatch(m -> "PRIVATE".equals(m.visibility()))
                        ? latestStatus(replay.projectId()) : replay.analysisStatus(),
                redact(owner, replay.repositories()));
    }

    @Transactional
    public Registration register(UUID owner, GitHubRepositoryClient.Repository repository, String name) {
        return register(owner, List.of(repository), name, null, null);
    }

    @Transactional
    public Registration register(UUID owner, List<GitHubRepositoryClient.Repository> repositories,
                                 String name, String requestKey, String requestHash) {
        return registerMixed(owner, repositories.stream().map(r -> new Repository(r, null, List.of())).toList(),
                name, requestKey, requestHash);
    }

    @Transactional
    public Registration registerMixed(UUID owner, List<Repository> repositories,
                                      String name, String requestKey, String requestHash) {
        requireRepositories(repositories);
        lockUser(owner);
        if (requestKey != null) {
            Registration replay = replayRegistration(owner, requestKey, requestHash);
            if (replay != null) return replay;
        }
        UUID projectId = UUID.randomUUID();
        var first = repositories.getFirst().identity();
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
        return addMixed(owner, projectId, repositories.stream().map(r -> new Repository(r, null, List.of())).toList(),
                requestKey, requestHash);
    }

    @Transactional
    public Addition addMixed(UUID owner, UUID projectId, List<Repository> repositories,
                             String requestKey, String requestHash) {
        requireRepositories(repositories);
        lockUser(owner);
        long version = lockProject(owner, projectId);
        if (requestKey != null) {
            Addition replay = replayAddition(owner, projectId, requestKey, requestHash);
            if (replay != null) return replay;
        }
        int existing = db.queryForObject("SELECT count(*) FROM project.project_repository " +
                "WHERE project_id=? AND removed_at IS NULL", Integer.class, projectId);
        if (existing + repositories.size() > 20)
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_repositories");
        boolean blocked = privateMembers(owner, projectId).stream().anyMatch(m -> !accessible(owner, m));
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
        Addition response = new Addition(projectId, nextVersion, blocked ? "CANCELLED" : "QUEUED",
                redact(owner, members));
        insertJob(projectId, nextVersion, "MEMBERSHIP_CHANGED", members, "ADD", requestKey, requestHash, response);
        if (blocked) cancelActiveJobs(projectId);
        return response;
    }

    @Transactional
    public void remove(UUID owner, UUID projectId, UUID memberId) {
        lockUser(owner);
        long version = lockProject(owner, projectId);
        var rows = db.query("SELECT removed_at IS NOT NULL,visibility FROM project.project_repository " +
                        "WHERE id=? AND project_id=? AND owner_user_id=? FOR UPDATE",
                (rs, row) -> new Object[]{rs.getBoolean(1), rs.getString(2)}, memberId, projectId, owner);
        if (rows.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "repository_not_found");
        if ((Boolean) rows.getFirst()[0]) return;
        boolean removingPrivate = "PRIVATE".equals(rows.getFirst()[1]);
        int count = db.queryForObject("SELECT count(*) FROM project.project_repository " +
                "WHERE project_id=? AND removed_at IS NULL", Integer.class, projectId);
        if (count <= 1) throw conflict("last_repository_required");
        // A-stage has no worker cancellation handshake; refuse removal while one is running.
        if (hasRunningJob(projectId) && !removingPrivate) throw conflict("analysis_in_progress");
        if (removingPrivate) cancelActiveJobs(projectId); else cancelQueuedJob(projectId);
        db.update("UPDATE project.project_repository SET removed_at=now() WHERE id=?", memberId);
        long nextVersion = version + 1;
        db.update("UPDATE project.registered_project SET membership_version=?,updated_at=now() WHERE id=?",
                nextVersion, projectId);
        if (privateMembers(owner, projectId).stream().allMatch(m -> accessible(owner, m)))
            insertJob(projectId, nextVersion, "MEMBERSHIP_CHANGED", membersForQueued(projectId),
                    null, null, null, null);
    }

    @Transactional
    public List<Item> list(UUID owner) {
        lockUser(owner);
        return db.query("SELECT p.id,p.display_name,j.state," +
                        "(SELECT count(*) FROM project.project_repository r WHERE r.project_id=p.id AND r.removed_at IS NULL)," +
                        "(SELECT count(*) FROM project.project_repository r WHERE r.project_id=p.id " +
                        "AND r.removed_at IS NULL AND r.visibility='PRIVATE') " +
                        "FROM project.registered_project p " +
                        "JOIN LATERAL (SELECT state FROM project.analysis_job WHERE project_id=p.id " +
                        "ORDER BY created_at DESC,id DESC LIMIT 1) j ON true " +
                        "WHERE p.owner_user_id=? ORDER BY p.created_at DESC,p.id DESC",
                (rs, row) -> {
                    UUID id = rs.getObject(1, UUID.class);
                    boolean blocked = privateMembers(owner, id).stream().anyMatch(m -> !accessible(owner, m));
                    return new Item(id, blocked ? "Private access unavailable" : rs.getString(2), safeStatus(rs.getString(3)),
                            rs.getInt(4), rs.getInt(5), false);
                }, owner);
    }

    @Transactional
    public Detail detail(UUID owner, UUID id) {
        lockUser(owner);
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
        List<Member> safe = redact(owner, members);
        boolean blocked = safe.stream().anyMatch(m -> "PRIVATE".equals(m.visibility()) &&
                "BLOCKED".equals(m.accessStatus()));
        return new Detail((UUID) row[0], blocked ? "Private access unavailable" : (String) row[1], (String) row[2],
                safeStatus((String) row[5]), false, null, (Long) row[3], safe, false);
    }

    public void requireOwned(UUID owner, UUID id) {
        var states = db.query("SELECT state FROM project.registered_project " +
                "WHERE id=? AND owner_user_id=?", (rs, row) -> rs.getString(1), id, owner);
        if (states.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "project_not_found");
        if (!"ACTIVE".equals(states.getFirst())) throw conflict("project_unavailable");
    }

    @Transactional
    public Revocation changeConsents(UUID owner, String repositoryId, List<Consent> decisions) {
        if (repositoryId == null || !repositoryId.matches("[1-9][0-9]{0,18}") || decisions == null ||
                decisions.size() != 2 || decisions.stream().map(Consent::purpose).distinct().count() != 2 ||
                decisions.stream().anyMatch(c -> !List.of("PRIVATE_COLLECTION", "EXTERNAL_AI").contains(c.purpose()) ||
                        !List.of("GRANTED", "DENIED", "REVOKED").contains(c.decision())))
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_request");
        lockUser(owner);
        var projects = projectIds(owner, "r.github_repository_id=? AND r.visibility='PRIVATE'", repositoryId);
        projects.forEach(id -> lockProject(owner, id));
        UUID revocationId = null;
        for (Consent decision : decisions) {
            var prior = db.query("SELECT decision,revocation_id,cleanup_state,policy_version FROM project.repository_consent " +
                            "WHERE owner_user_id=? AND github_repository_id=? AND purpose=? " +
                            "ORDER BY event_order DESC LIMIT 1",
                    (rs, n) -> new Object[]{rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4)},
                    owner, repositoryId, decision.purpose());
            Object[] latest = prior.isEmpty() ? null : prior.getFirst();
            String old = latest == null ? null : (String) latest[0];
            if ("GRANTED".equals(decision.decision())) {
                if (latest != null && "REVOKED".equals(old) && !"NO_DERIVED_DATA".equals(latest[2]))
                    throw conflict("revocation_in_progress");
                connections.requirePrivateRepository(owner, repositoryId);
            } else if ("REVOKED".equals(decision.decision())) {
                if (latest == null || !"GRANTED".equals(old) && !"REVOKED".equals(old))
                    throw new ApiProblem(HttpStatus.FORBIDDEN, "consent_not_owned");
                if ("REVOKED".equals(old)) {
                    revocationId = (UUID) latest[1];
                    continue;
                }
                if (revocationId == null) revocationId = UUID.randomUUID();
            } else if ("GRANTED".equals(old) || "REVOKED".equals(old)) {
                throw conflict("revocation_required");
            }
            if (decision.decision().equals(old) && decision.policyVersion().equals(latest[3])) continue;
            db.update("INSERT INTO project.repository_consent" +
                            "(id,owner_user_id,github_repository_id,purpose,decision,policy_version,revocation_id,cleanup_state) " +
                            "VALUES (?,?,?,?,?,?,?,?)", UUID.randomUUID(), owner, repositoryId, decision.purpose(),
                    decision.decision(), decision.policyVersion(),
                    "REVOKED".equals(decision.decision()) ? revocationId : null,
                    "REVOKED".equals(decision.decision()) ? "NO_DERIVED_DATA" : null);
            if ("PRIVATE_COLLECTION".equals(decision.purpose())) {
                if ("REVOKED".equals(decision.decision())) blockRepository(owner, repositoryId);
                if ("GRANTED".equals(decision.decision())) {
                    boolean wasBlocked = Boolean.TRUE.equals(db.queryForObject(
                            "SELECT EXISTS(SELECT 1 FROM project.project_repository WHERE owner_user_id=? " +
                            "AND github_repository_id=? AND access_state='BLOCKED' AND removed_at IS NULL)",
                            Boolean.class, owner, repositoryId));
                    db.update("UPDATE project.project_repository SET access_state='ACTIVE' " +
                            "WHERE owner_user_id=? AND github_repository_id=? AND removed_at IS NULL " +
                            "AND visibility='PRIVATE'", owner, repositoryId);
                    if (wasBlocked) for (UUID projectId : projects) {
                        long version = db.queryForObject("SELECT membership_version FROM project.registered_project " +
                                "WHERE id=?", Long.class, projectId) + 1;
                        db.update("UPDATE project.registered_project SET membership_version=?,updated_at=now() WHERE id=?",
                                version, projectId);
                        if (privateMembers(owner, projectId).stream().allMatch(m -> accessible(owner, m))) {
                            cancelQueuedJob(projectId);
                            insertJob(projectId, version, "MEMBERSHIP_CHANGED", membersForQueued(projectId),
                                    null, null, null, null);
                        }
                    }
                }
            }
        }
        return new Revocation(revocationId, revocationId == null ? "UPDATED" : "BLOCKED");
    }

    @Transactional
    public Disconnection disconnect(UUID owner, UUID connectionId) {
        lockUser(owner);
        var projects = projectIds(owner, "r.github_connection_id=?", connectionId);
        projects.forEach(id -> lockProject(owner, id));
        var state = db.query("SELECT user_state FROM project.github_connection " +
                "WHERE id=? AND devfield_user_id=? FOR UPDATE", (rs, n) -> rs.getString(1), connectionId, owner);
        if (state.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "connection_not_found");
        if (!"DISCONNECTED".equals(state.getFirst())) {
            blockConnection(owner, connectionId);
            db.update("UPDATE project.github_connection SET generation=generation+1,user_state='DISCONNECTED'," +
                    "installation_state='NOT_INSTALLED',installation_id=NULL,access_token_ciphertext=NULL," +
                    "refresh_token_ciphertext=NULL,token_expires_at=NULL,refresh_expires_at=NULL," +
                    "token_key_version=NULL,updated_at=now() WHERE id=?", connectionId);
        }
        return new Disconnection(connectionId, "BLOCKED");
    }

    @Transactional
    public void blockInstallation(long installationId, List<String> repositoryIds) {
        var owners = db.query("SELECT devfield_user_id FROM project.github_connection " +
                "WHERE installation_id=? ORDER BY devfield_user_id", (rs, n) -> rs.getObject(1, UUID.class), installationId);
        for (UUID owner : owners) {
            lockUser(owner);
            var connections = db.query("SELECT id FROM project.github_connection WHERE devfield_user_id=? " +
                    "AND installation_id=?", (rs, n) -> rs.getObject(1, UUID.class), owner, installationId);
            if (connections.isEmpty()) continue;
            UUID connectionId = connections.getFirst();
            var projects = projectIds(owner, "r.github_connection_id=?", connectionId);
            projects.forEach(id -> lockProject(owner, id));
            if (repositoryIds == null) {
                blockConnection(owner, connectionId);
                db.update("UPDATE project.github_connection SET installation_state='NOT_INSTALLED'," +
                        "installation_id=NULL,updated_at=now() WHERE id=?", connectionId);
            } else for (String repositoryId : repositoryIds) blockRepository(owner, repositoryId);
        }
    }

    @Transactional
    public void blockGitHubUser(long githubUserId) {
        var owners = db.query("SELECT devfield_user_id,id FROM project.github_connection WHERE github_user_id=?",
                (rs, n) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}, githubUserId);
        for (UUID[] pair : owners) disconnect(pair[0], pair[1]);
    }

    private List<UUID> projectIds(UUID owner, String predicate, Object value) {
        return db.query("SELECT DISTINCT r.project_id FROM project.project_repository r " +
                        "WHERE r.owner_user_id=? AND r.removed_at IS NULL AND " + predicate + " ORDER BY r.project_id",
                (rs, n) -> rs.getObject(1, UUID.class), owner, value);
    }

    private void blockConnection(UUID owner, UUID connectionId) {
        var ids = projectIds(owner, "r.github_connection_id=?", connectionId);
        db.update("UPDATE project.project_repository SET access_state='BLOCKED' WHERE owner_user_id=? " +
                "AND github_connection_id=? AND visibility='PRIVATE' AND removed_at IS NULL", owner, connectionId);
        for (UUID id : ids) {
            cancelActiveJobs(id);
            db.update("UPDATE project.registered_project SET membership_version=membership_version+1," +
                    "updated_at=now() WHERE id=?", id);
        }
    }

    private void blockRepository(UUID owner, String repositoryId) {
        var ids = projectIds(owner, "r.github_repository_id=? AND r.visibility='PRIVATE'", repositoryId);
        db.update("UPDATE project.project_repository SET access_state='BLOCKED' WHERE owner_user_id=? " +
                "AND github_repository_id=? AND visibility='PRIVATE' AND removed_at IS NULL", owner, repositoryId);
        for (UUID id : ids) {
            cancelActiveJobs(id);
            db.update("UPDATE project.registered_project SET membership_version=membership_version+1," +
                    "updated_at=now() WHERE id=?", id);
        }
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

    private void cancelActiveJobs(UUID projectId) {
        db.update("UPDATE project.analysis_job SET state='CANCELLED',finished_at=now()," +
                "repository_progress=(SELECT COALESCE(jsonb_agg(jsonb_set(x,'{state}','\"CANCELLED\"'::jsonb))," +
                "'[]'::jsonb) FROM jsonb_array_elements(repository_progress) x) " +
                "WHERE project_id=? AND state IN ('QUEUED','RUNNING')", projectId);
    }

    private void insertMembers(UUID projectId, UUID owner, List<Repository> repositories) {
        for (var repository : repositories) {
            var identity = repository.identity();
            if (repository.connectionId() != null &&
                    !repository.connectionId().equals(connections.requirePrivateRepository(owner, identity.id())))
                throw new ApiProblem(HttpStatus.FORBIDDEN, "private_access_unavailable");
            db.update("INSERT INTO project.project_repository" +
                            "(id,project_id,owner_user_id,github_repository_id,repository_full_name,visibility,github_connection_id) " +
                            "VALUES (?,?,?,?,?,?,?)",
                    UUID.randomUUID(), projectId, owner, identity.id(), identity.fullName(),
                    repository.connectionId() == null ? "PUBLIC" : "PRIVATE", repository.connectionId());
            for (Consent consent : repository.consents())
                db.update("INSERT INTO project.repository_consent" +
                                "(id,owner_user_id,github_repository_id,purpose,decision,policy_version) VALUES (?,?,?,?,?,?)",
                        UUID.randomUUID(), owner, identity.id(), consent.purpose(), consent.decision(), consent.policyVersion());
        }
    }

    private List<Member> privateMembers(UUID owner, UUID projectId) {
        return db.query("SELECT id,github_repository_id,repository_full_name,visibility,access_state " +
                        "FROM project.project_repository WHERE owner_user_id=? AND removed_at IS NULL " +
                        "AND visibility='PRIVATE'" + (projectId == null ? "" : " AND project_id=?"),
                (rs, row) -> new Member(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), null, null),
                projectId == null ? new Object[]{owner} : new Object[]{owner, projectId});
    }

    private void checkPrivateAccess(UUID owner, List<Member> members) {
        for (Member member : members) {
            if (!"PRIVATE".equals(member.visibility())) continue;
            Consent consent = db.query("SELECT decision,policy_version FROM project.repository_consent WHERE owner_user_id=? " +
                            "AND github_repository_id=? AND purpose='PRIVATE_COLLECTION' " +
                            "ORDER BY event_order DESC LIMIT 1",
                    (rs, row) -> new Consent("PRIVATE_COLLECTION", rs.getString(1), rs.getString(2)),
                    owner, member.githubRepositoryId()).stream().findFirst().orElse(null);
            if (!"ACTIVE".equals(member.accessStatus()) || consent == null ||
                    !"GRANTED".equals(consent.decision()) || !policyVersion.equals(consent.policyVersion()) ||
                    policyVersion.isBlank())
                throw new ApiProblem(HttpStatus.FORBIDDEN, "private_access_unavailable");
            UUID boundConnection = db.query("SELECT github_connection_id FROM project.project_repository " +
                            "WHERE id=? AND owner_user_id=? AND removed_at IS NULL AND visibility='PRIVATE'",
                    (rs, row) -> rs.getObject(1, UUID.class), member.id(), owner).stream().findFirst().orElse(null);
            if (boundConnection == null ||
                    !boundConnection.equals(connections.requirePrivateRepository(owner, member.githubRepositoryId())))
                throw new ApiProblem(HttpStatus.FORBIDDEN, "private_access_unavailable");
        }
    }

    private boolean accessible(UUID owner, Member member) {
        if (!"PRIVATE".equals(member.visibility())) return true;
        try { checkPrivateAccess(owner, List.of(member)); return true; }
        catch (ApiProblem e) { return false; }
    }

    private List<Member> redact(UUID owner, List<Member> members) {
        return members.stream().map(m -> accessible(owner, m) ? m : new Member(m.id(), null, null,
                m.visibility(), "BLOCKED", m.analysisStatus(), null)).toList();
    }

    private List<Member> membersForQueued(UUID projectId) {
        return db.query("SELECT id,github_repository_id,repository_full_name,visibility,access_state " +
                        "FROM project.project_repository WHERE project_id=? AND removed_at IS NULL " +
                        "ORDER BY added_at,id",
                (rs, row) -> new Member(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), "QUEUED", null), projectId);
    }

    private String latestStatus(UUID projectId) {
        return safeStatus(db.queryForObject("SELECT state FROM project.analysis_job WHERE project_id=? " +
                "ORDER BY created_at DESC,id DESC LIMIT 1", String.class, projectId));
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

    private static void requireRepositories(List<Repository> repositories) {
        if (repositories == null || repositories.isEmpty() || repositories.size() > 20)
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_repositories");
        var ids = new HashSet<String>();
        for (var repository : repositories) {
            if (repository == null || repository.identity() == null || repository.identity().id() == null ||
                    repository.identity().id().isBlank() || repository.identity().fullName() == null ||
                    repository.identity().fullName().isBlank())
                throw new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_repositories");
            if (!ids.add(repository.identity().id())) throw conflict("repository_already_registered");
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
