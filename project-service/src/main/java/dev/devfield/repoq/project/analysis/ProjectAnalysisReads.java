package dev.devfield.repoq.project.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.devfield.repoq.project.api.ApiProblem;
import dev.devfield.repoq.project.data.ProjectWriteFence;
import dev.devfield.repoq.project.github.GitHubSourceClient;
import org.springframework.http.HttpStatus;
import dev.devfield.repoq.project.data.Projects;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ProjectAnalysisReads {
    public record SnapshotMember(UUID memberId, UUID repositoryId, String githubRepositoryId, String commitSha) {}
    public record Evidence(UUID id, UUID memberId, UUID repositoryId, String githubRepositoryId, String commitSha,
                           String filePath, Integer lineStart, Integer lineEnd, String claimType, String summary) {}
    public record Snapshot(UUID snapshotId, long membershipVersion, List<SnapshotMember> members, List<String> topics,
                           List<AnalysisSnapshots.Relation> relations, List<AnalysisSnapshots.Coverage> coverage,
                           List<Evidence> evidenceReferences) {}
    public record InterviewTopic(String key, UUID primaryEvidenceId) {}
    public record InterviewData(UUID snapshotId, long membershipVersion, List<SnapshotMember> members,
                                List<InterviewTopic> topics, List<AnalysisSnapshots.Relation> relations,
                                List<Evidence> evidenceReferences) {}

    private final Projects projects;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Optional<PublicAccessVerifier> verifier;
    private final ObjectMapper json = new ObjectMapper();

    public ProjectAnalysisReads(Projects projects, JdbcTemplate db, TransactionTemplate tx, Optional<PublicAccessVerifier> verifier) {
        this.projects = projects; this.db = db; this.tx = tx; this.verifier = verifier;
    }

    public List<Projects.Item> list(UUID owner) {
        return projects.list(owner).stream().map(item -> {
            var candidates = db.query("""
                    SELECT CASE WHEN state='DELETING' THEN 'DELETING'
                                WHEN latest_ready_snapshot_id IS NOT NULL THEN 'SNAPSHOT' ELSE 'NONE' END
                    FROM project.registered_project WHERE id=? AND owner_user_id=?
                    """, (rs, n) -> rs.getString(1), item.id(), owner);
            if (candidates.isEmpty()) return null;
            String candidate = candidates.getFirst();
            if ("DELETING".equals(candidate))
                return new Projects.Item(item.id(), "Project deleting", "CANCELLED",
                        item.repositoryCount(), item.privateRepositoryCount(), false);
            if (!"SNAPSHOT".equals(candidate)) return item;
            try {
                Projects.Detail detail = detail(owner, item.id());
                return new Projects.Item(item.id(), detail.name(), detail.analysisStatus(),
                        item.repositoryCount(), item.privateRepositoryCount(), false);
            } catch (ApiProblem failure) {
                if (failure.status() == HttpStatus.NOT_FOUND) return null;
                throw failure;
            }
        }).filter(Objects::nonNull).toList();
    }

    public Projects.Detail detail(UUID owner, UUID project) {
        // Existing ownership/session-facing behavior runs first; no remote lookup for someone else's project.
        Projects.Detail base = projects.detail(owner, project);
        if ("DELETING".equals(base.projectStatus())) return base;
        Snapshot snapshot = candidate(owner, project, base.membershipVersion());
        Set<PublicAccessVerifier.Source> sourceSet = new LinkedHashSet<>();
        for (var m : base.repositories()) if ("PUBLIC".equals(m.visibility()) && m.commitSha() != null)
            sourceSet.add(new PublicAccessVerifier.Source(m.id(), m.githubRepositoryId(), m.commitSha()));
        if (snapshot != null) for (var m : snapshot.members())
            sourceSet.add(new PublicAccessVerifier.Source(m.repositoryId(), m.githubRepositoryId(), m.commitSha()));
        if (sourceSet.isEmpty()) return base;
        List<PublicAccessVerifier.Source> sources = List.copyOf(sourceSet);
        PublicAccessVerifier.Proof proof = detailProof(sources);
        return tx.execute(status -> {
            ProjectWriteFence.lockUser(db, owner);
            var states = db.query("SELECT state FROM project.registered_project WHERE id=? AND owner_user_id=?",
                    (rs, row) -> rs.getString(1), project, owner);
            if (states.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "project_not_found");
            if ("DELETING".equals(states.getFirst())) return Projects.deleting(base);
            if (proof == null || !proof.validFor(sources)) return blocked(base);
            long version = ProjectWriteFence.lockProject(db, owner, project);
            if (version != base.membershipVersion()) return blocked(base);
            Snapshot current = candidate(owner, project, version);
            if (!Objects.equals(snapshot == null ? null : snapshot.snapshotId(), current == null ? null : current.snapshotId()))
                return blocked(base);
            var active = db.query("SELECT id FROM project.project_repository WHERE project_id=? AND removed_at IS NULL " +
                    "AND visibility='PUBLIC' AND access_state='ACTIVE'", (rs,n) -> rs.getObject(1, UUID.class), project);
            if (!active.containsAll(sources.stream().map(PublicAccessVerifier.Source::repositoryId).toList())) return blocked(base);
            String state = db.queryForObject("SELECT state FROM project.analysis_job WHERE project_id=? ORDER BY created_at DESC,id DESC LIMIT 1",
                    String.class, project);
            String safeState = "READY".equals(state) && current == null ? "FAILED" : state;
            Map<UUID, String> progress = new HashMap<>();
            db.query("SELECT x->>'repositoryId',x->>'state' FROM " +
                    "(SELECT repository_progress FROM project.analysis_job WHERE project_id=? ORDER BY created_at DESC,id DESC LIMIT 1) j, " +
                    "jsonb_array_elements(j.repository_progress) x", rs -> {
                String memberState = rs.getString(2);
                // Collection completion alone is not a published analysis result.
                if ("READY".equals(memberState)) memberState = "READY".equals(safeState) ? "READY" :
                        Set.of("QUEUED", "RUNNING").contains(safeState) ? "RUNNING" : safeState;
                progress.put(UUID.fromString(rs.getString(1)), memberState);
            }, project);
            List<Projects.Member> members = base.repositories().stream().map(m -> new Projects.Member(
                    m.id(), m.githubRepositoryId(), m.fullName(), m.visibility(), m.accessStatus(),
                    progress.getOrDefault(m.id(), m.analysisStatus()), m.commitSha())).toList();
            if (!proof.validFor(sources)) return blocked(base);
            return new Projects.Detail(base.id(), base.name(), base.projectStatus(), safeState, false, current,
                    version, members, false);
        });
    }

    private PublicAccessVerifier.Proof detailProof(List<PublicAccessVerifier.Source> sources) {
        try {
            return verifier.orElseThrow().verify(sources);
        } catch (RuntimeException denied) {
            return null;
        }
    }

    public InterviewData interviewData(UUID owner, UUID project) {
        Snapshot selected = tx.execute(status -> interviewCandidate(owner, project));
        var sources = selected.members().stream().map(m ->
                new PublicAccessVerifier.Source(m.repositoryId(), m.githubRepositoryId(), m.commitSha())).toList();
        PublicAccessVerifier.Proof proof;
        try {
            proof = verifier.orElseThrow().verify(sources);
        } catch (GitHubSourceClient.Failure failure) {
            throw switch (failure.code()) {
                case "repository_not_public", "repository_id_mismatch", "github_not_found", "github_access_denied" ->
                        new ApiProblem(HttpStatus.FORBIDDEN, "repository_access_unavailable");
                case "github_rate_limited" -> new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_rate_limited");
                case "github_timeout" -> new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_timeout");
                default -> new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_unavailable");
            };
        } catch (RuntimeException failure) {
            throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_unavailable");
        }
        if (proof == null || !proof.validFor(sources))
            throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_unavailable");

        return tx.execute(status -> {
            Snapshot current = interviewCandidate(owner, project);
            if (!selected.snapshotId().equals(current.snapshotId()) ||
                    selected.membershipVersion() != current.membershipVersion() || !selected.members().equals(current.members()))
                throw new ApiProblem(HttpStatus.CONFLICT, "snapshot_changed");
            var topics = db.query("SELECT topic_key,primary_evidence_id FROM project.available_topic " +
                            "WHERE snapshot_id=? AND is_active ORDER BY topic_key LIMIT 65",
                    (rs, n) -> new InterviewTopic(rs.getString(1), rs.getObject(2, UUID.class)), current.snapshotId());
            Map<UUID, Evidence> facts = new HashMap<>();
            for (Evidence evidence : current.evidenceReferences()) {
                if ("CODE_FACT".equals(evidence.claimType())) facts.put(evidence.id(), evidence);
            }
            if (topics.isEmpty() || topics.size() > 64 ||
                    topics.stream().anyMatch(topic -> !facts.containsKey(topic.primaryEvidenceId())))
                throw new ApiProblem(HttpStatus.CONFLICT, "snapshot_unavailable");

            // The current Worker publishes no relations. Keep only relations supported by transmitted code facts.
            var relations = current.relations().stream().filter(relation ->
                    "CODE_FACT".equals(relation.claimType()) && !relation.evidenceIds().isEmpty() &&
                            facts.keySet().containsAll(relation.evidenceIds())).toList();
            Set<UUID> included = new HashSet<>();
            topics.forEach(topic -> included.add(topic.primaryEvidenceId()));
            relations.forEach(relation -> included.addAll(relation.evidenceIds()));
            var evidence = current.evidenceReferences().stream().filter(ref -> included.contains(ref.id())).toList();
            if (!proof.validFor(sources)) throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_unavailable");
            return new InterviewData(current.snapshotId(), current.membershipVersion(), current.members(),
                    topics, relations, evidence);
        });
    }

    private Snapshot interviewCandidate(UUID owner, UUID project) {
        // Check ownership before creating or locking the user fence, and before any GitHub request.
        projects.requireOwned(owner, project);
        ProjectWriteFence.lockUser(db, owner);
        long version = ProjectWriteFence.lockProject(db, owner, project);
        if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM project.project_repository " +
                "WHERE project_id=? AND removed_at IS NULL AND visibility<>'PUBLIC')", Boolean.class, project)))
            throw new ApiProblem(HttpStatus.FORBIDDEN, "private_analysis_not_supported");
        Snapshot snapshot = candidate(owner, project, version);
        if (snapshot == null || snapshot.members().size() > 20 || snapshot.evidenceReferences().size() > 200 ||
                snapshot.relations().size() > 100)
            throw new ApiProblem(HttpStatus.CONFLICT, "snapshot_unavailable");
        return snapshot;
    }

    private Snapshot candidate(UUID owner, UUID project, long version) {
        var snapshots = db.query("SELECT s.id,s.membership_version,s.relations::text,s.coverage::text FROM project.registered_project p " +
                "JOIN project.analysis_snapshot s ON s.id=p.latest_ready_snapshot_id AND s.project_id=p.id " +
                "JOIN project.analysis_job j ON j.id=s.analysis_job_id AND j.state='READY' " +
                "WHERE p.id=? AND p.owner_user_id=? AND p.state='ACTIVE' AND s.access_state='ACTIVE' " +
                "AND s.membership_version=? AND p.membership_version=s.membership_version",
                (rs,n) -> new Object[]{rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3), rs.getString(4)}, project, owner, version);
        if (snapshots.isEmpty()) return null;
        Object[] row = snapshots.getFirst(); UUID id = (UUID) row[0];
        var members = db.query("SELECT m.id,m.project_repository_id,m.github_repository_id,m.commit_sha FROM project.snapshot_member m " +
                "JOIN project.project_repository r ON r.id=m.project_repository_id AND r.project_id=m.project_id " +
                "WHERE m.snapshot_id=? AND r.removed_at IS NULL AND r.visibility='PUBLIC' AND r.access_state='ACTIVE' ORDER BY m.id",
                (rs,n) -> new SnapshotMember(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4)), id);
        int count = db.queryForObject("SELECT count(*) FROM project.project_repository WHERE project_id=? AND removed_at IS NULL", Integer.class, project);
        int snapshotCount = db.queryForObject("SELECT count(*) FROM project.snapshot_member WHERE snapshot_id=?", Integer.class, id);
        if (count == 0 || count != members.size() || snapshotCount != count) return null;
        var topics = db.query("SELECT topic_key FROM project.available_topic WHERE snapshot_id=? AND is_active ORDER BY topic_key",
                (rs,n) -> rs.getString(1), id);
        if (topics.isEmpty()) return null;
        var evidence = db.query("SELECT e.id,e.snapshot_member_id,m.project_repository_id,e.github_repository_id,e.commit_sha," +
                "e.file_path,e.line_start,e.line_end,e.claim_type,e.summary FROM project.evidence_reference e " +
                "JOIN project.snapshot_member m ON m.id=e.snapshot_member_id AND m.snapshot_id=e.snapshot_id WHERE e.snapshot_id=? ORDER BY e.id",
                (rs,n) -> new Evidence(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getString(5), rs.getString(6), (Integer)rs.getObject(7), (Integer)rs.getObject(8),
                        rs.getString(9), rs.getString(10)), id);
        try {
            return new Snapshot(id, (Long)row[1], members, topics,
                    json.readValue((String)row[2], new TypeReference<List<AnalysisSnapshots.Relation>>() {}),
                    json.readValue((String)row[3], new TypeReference<List<AnalysisSnapshots.Coverage>>() {}), evidence);
        } catch (Exception e) { return null; }
    }

    private Projects.Detail blocked(Projects.Detail base) {
        var members = base.repositories().stream().map(m -> "PUBLIC".equals(m.visibility())
                ? new Projects.Member(m.id(), null, null, m.visibility(), "BLOCKED",
                "READY".equals(m.analysisStatus()) ? "FAILED" : m.analysisStatus(), null) : m).toList();
        return new Projects.Detail(base.id(), "Public access unavailable", base.projectStatus(),
                "READY".equals(base.analysisStatus()) ? "FAILED" : base.analysisStatus(),
                false, null, base.membershipVersion(), members, false);
    }
}
