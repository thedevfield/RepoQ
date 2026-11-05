package dev.devfield.repoq.project.analysis;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class AnalysisSnapshots {
    public enum ClaimType { CODE_FACT, DOC_CLAIM, HYPOTHESIS }
    public record Member(UUID id, UUID repositoryId, String githubRepositoryId, String commitSha) {}
    public record Evidence(UUID id, UUID memberId, String path, Integer lineStart, Integer lineEnd,
                           ClaimType claimType, String summary) {}
    public record Topic(String key, boolean active, UUID evidenceId) {}
    public record Role(UUID memberId, ClaimType claimType, List<UUID> evidenceIds, String summary) {
        public Role { evidenceIds = List.copyOf(evidenceIds); }
    }
    public record Relation(UUID fromMemberId, UUID toMemberId, ClaimType claimType,
                           List<UUID> evidenceIds, String summary) {
        public Relation { evidenceIds = List.copyOf(evidenceIds); }
    }
    public record Coverage(UUID memberId, int collectedFiles, int skippedFiles) {}
    public record Draft(List<Member> members, List<Evidence> evidence, List<Topic> topics,
                        List<Role> roles, List<Relation> relations, List<Coverage> coverage) {
        public Draft {
            members = List.copyOf(members); evidence = List.copyOf(evidence); topics = List.copyOf(topics);
            roles = List.copyOf(roles); relations = List.copyOf(relations); coverage = List.copyOf(coverage);
        }
    }

    private final JdbcTemplate db;
    private final AnalysisJobs jobs;
    private final TransactionTemplate tx;
    private final Optional<PublicAccessVerifier> publicAccess;

    public AnalysisSnapshots(JdbcTemplate db, AnalysisJobs jobs, TransactionTemplate tx,
                             Optional<PublicAccessVerifier> publicAccess) {
        this.db = db; this.jobs = jobs; this.tx = tx; this.publicAccess = publicAccess;
    }

    public UUID publish(AnalysisJobs.Execution execution, Draft draft) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Public access verification must run outside a transaction");
        validate(draft);
        // No production verifier exists in A-stage: DB visibility or a caller-supplied flag is never approval.
        PublicAccessVerifier verifier = publicAccess.orElseThrow(() -> new IllegalStateException("Public access verifier unavailable"));
        var sources = draft.members().stream().map(m -> new PublicAccessVerifier.Source(
                m.repositoryId(), m.githubRepositoryId(), m.commitSha())).toList();
        if (!verifier.verify(sources)) throw new IllegalStateException("Public access not verified");
        return tx.execute(status -> {
            AnalysisJobs.Locked job = jobs.current(execution);
            if (job.members().size() != draft.members().size()) throw invalid();
            for (Member member : draft.members()) {
                if (job.members().stream().noneMatch(m -> m.id().equals(member.repositoryId()) && m.githubId().equals(member.githubRepositoryId())) ||
                        job.progress().stream().noneMatch(p -> p.repositoryId().equals(member.repositoryId()) &&
                                "READY".equals(p.state()) && member.commitSha().equals(p.commitSha()))) throw invalid();
            }
            if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM project.analysis_snapshot WHERE analysis_job_id=?)",
                    Boolean.class, job.id()))) throw new IllegalStateException("Snapshot already published");
            UUID snapshot = UUID.randomUUID();
            db.update("INSERT INTO project.analysis_snapshot(id,project_id,analysis_job_id,membership_version,analysis_version," +
                    "roles,relations,coverage) VALUES (?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb))",
                    snapshot, job.projectId(), job.id(), job.version(), job.analysisVersion(),
                    jobs.encode(draft.roles()), jobs.encode(draft.relations()), jobs.encode(draft.coverage()));
            Map<UUID, Member> members = new HashMap<>();
            for (Member m : draft.members()) {
                members.put(m.id(), m);
                db.update("INSERT INTO project.snapshot_member(id,snapshot_id,project_id,project_repository_id," +
                        "github_repository_id,commit_sha,visibility_at_analysis) VALUES (?,?,?,?,?,?,'PUBLIC')",
                        m.id(), snapshot, job.projectId(), m.repositoryId(), m.githubRepositoryId(), m.commitSha());
            }
            for (Evidence e : draft.evidence()) {
                Member m = members.get(e.memberId());
                db.update("INSERT INTO project.evidence_reference(id,snapshot_id,snapshot_member_id,github_repository_id," +
                        "commit_sha,file_path,line_start,line_end,claim_type,summary) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        e.id(), snapshot, e.memberId(), m.githubRepositoryId(), m.commitSha(), e.path(),
                        e.lineStart(), e.lineEnd(), e.claimType().name(), e.summary());
            }
            for (Topic t : draft.topics()) db.update("INSERT INTO project.available_topic" +
                    "(id,snapshot_id,topic_key,is_active,primary_evidence_id) VALUES (?,?,?,?,?)",
                    UUID.randomUUID(), snapshot, t.key(), t.active(), t.evidenceId());
            // Recheck the lease after inserts as well: a slow publication must not outlive its execution.
            jobs.current(execution);
            db.update("UPDATE project.registered_project SET latest_ready_snapshot_id=?,updated_at=clock_timestamp() WHERE id=?",
                    snapshot, job.projectId());
            jobs.writeCurrent(execution, "state='READY',finished_at=clock_timestamp(),lease_expires_at=NULL,failure_code=NULL");
            return snapshot;
        });
    }

    private static void validate(Draft d) {
        if (d == null || d.members().isEmpty() || d.members().size() > 20 || d.evidence().size() > 200 ||
                d.topics().size() > 64 || d.roles().size() > 100 || d.relations().size() > 100) throw invalid();
        Map<UUID, Member> members = new HashMap<>();
        Set<UUID> repositories = new HashSet<>();
        Set<String> githubIds = new HashSet<>();
        for (Member m : d.members()) {
            if (m.id() == null || m.repositoryId() == null || m.githubRepositoryId() == null ||
                    !m.githubRepositoryId().matches("[1-9][0-9]{0,18}") || !AnalysisJobs.validSha(m.commitSha()) ||
                    members.put(m.id(), m) != null || !repositories.add(m.repositoryId()) || !githubIds.add(m.githubRepositoryId())) throw invalid();
        }
        Map<UUID, Evidence> evidence = new HashMap<>();
        for (Evidence e : d.evidence()) {
            if (e.id() == null || !members.containsKey(e.memberId()) || e.claimType() == null ||
                    !validPath(e.path()) || !validSummary(e.summary()) ||
                    (e.lineStart() == null) != (e.lineEnd() == null) ||
                    e.lineStart() != null && (e.lineStart() < 1 || e.lineEnd() < e.lineStart()) ||
                    evidence.put(e.id(), e) != null) throw invalid();
        }
        Set<String> topics = new HashSet<>();
        boolean usable = false;
        for (Topic t : d.topics()) {
            if (t.key() == null || !t.key().matches("[A-Z][A-Z0-9_]{0,63}") || !topics.add(t.key()) ||
                    t.evidenceId() != null && !evidence.containsKey(t.evidenceId())) throw invalid();
            if (t.active()) {
                if (!evidence.containsKey(t.evidenceId()) || evidence.get(t.evidenceId()).claimType() != ClaimType.CODE_FACT) throw invalid();
                usable = true;
            }
        }
        if (!usable) throw invalid();
        for (Role r : d.roles()) {
            if (!members.containsKey(r.memberId()) || !validSummary(r.summary())) throw invalid();
            checkReferences(r.claimType(), r.evidenceIds(), Set.of(r.memberId()), evidence);
        }
        for (Relation r : d.relations()) {
            if (!members.containsKey(r.fromMemberId()) || !members.containsKey(r.toMemberId()) ||
                    r.fromMemberId().equals(r.toMemberId()) || !validSummary(r.summary())) throw invalid();
            checkReferences(r.claimType(), r.evidenceIds(), Set.of(r.fromMemberId(), r.toMemberId()), evidence);
        }
        Set<UUID> covered = new HashSet<>();
        for (Coverage c : d.coverage()) {
            if (!members.containsKey(c.memberId()) || !covered.add(c.memberId()) || c.collectedFiles() < 0 ||
                    c.collectedFiles() > 40 || c.skippedFiles() < 0) throw invalid();
        }
        if (!covered.equals(members.keySet()) || d.coverage().stream().mapToInt(Coverage::collectedFiles).sum() > 40) throw invalid();
    }

    private static void checkReferences(ClaimType type, List<UUID> ids, Set<UUID> expected, Map<UUID, Evidence> evidence) {
        if (type == null || ids.isEmpty() || ids.size() > 200 || new HashSet<>(ids).size() != ids.size()) throw invalid();
        Set<UUID> sources = new HashSet<>();
        for (UUID id : ids) {
            Evidence e = evidence.get(id);
            if (e == null || type == ClaimType.CODE_FACT && e.claimType() != ClaimType.CODE_FACT ||
                    type == ClaimType.DOC_CLAIM && e.claimType() == ClaimType.HYPOTHESIS) throw invalid();
            sources.add(e.memberId());
        }
        if (!sources.equals(expected)) throw invalid();
    }

    private static boolean validPath(String path) {
        if (path == null || path.isBlank() || path.length() > 1024 || path.contains("\\") || path.contains(":") ||
                path.chars().anyMatch(Character::isISOControl)) return false;
        return Arrays.stream(path.split("/", -1)).noneMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."));
    }
    private static boolean validSummary(String summary) {
        return summary != null && !summary.isBlank() && summary.length() <= 1000 &&
                summary.chars().noneMatch(Character::isISOControl);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid analysis snapshot"); }
}
