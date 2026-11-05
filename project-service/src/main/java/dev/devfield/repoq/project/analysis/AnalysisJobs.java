package dev.devfield.repoq.project.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.devfield.repoq.project.data.ProjectWriteFence;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AnalysisJobs {
    public record Execution(UUID jobId, UUID projectId, long membershipVersion, int attempt) {}
    public record Progress(UUID repositoryId, String state, String commitSha, String failureCode) {}
    record Member(UUID id, String githubId, String visibility, String accessState) {}
    record Locked(UUID id, UUID projectId, long version, long currentVersion, String state,
                  int attempt, boolean leaseValid, boolean due, String analysisVersion,
                  List<Progress> progress, List<Member> members) {}

    private final JdbcTemplate db;
    private final AnalysisSettings settings;
    private final ObjectMapper json = new ObjectMapper();

    public AnalysisJobs(JdbcTemplate db, AnalysisSettings settings) { this.db = db; this.settings = settings; }

    public List<UUID> queued(int limit) {
        checkLimit(limit);
        return db.query("SELECT id FROM project.analysis_job WHERE state='QUEUED' AND next_attempt_at<=clock_timestamp() " +
                "ORDER BY next_attempt_at,created_at,id LIMIT ?", (rs, n) -> rs.getObject(1, UUID.class), limit);
    }

    public List<UUID> expired(int limit) {
        checkLimit(limit);
        return db.query("SELECT id FROM project.analysis_job WHERE state='RUNNING' " +
                "AND (lease_expires_at IS NULL OR lease_expires_at<=clock_timestamp() OR started_at IS NULL OR " +
                "started_at+make_interval(secs=>?)<=clock_timestamp()) ORDER BY id LIMIT ?",
                (rs, n) -> rs.getObject(1, UUID.class), settings.maxRunSeconds(), limit);
    }

    @Transactional
    public Execution claim(UUID id) {
        Locked job = lock(id);
        if (!"QUEUED".equals(job.state()) || !job.due()) return null;
        if (!compositionMatches(job)) { terminal(job.id(), "CANCELLED", "membership_changed"); return null; }
        if (!publicMembers(job)) { terminal(job.id(), "FAILED", accessFailure(job)); return null; }
        if (job.attempt() >= settings.maxAttempts()) { terminal(id, "FAILED", "attempts_exhausted"); return null; }
        db.update("UPDATE project.analysis_job SET state='RUNNING',attempt_count=attempt_count+1," +
                "started_at=clock_timestamp(),finished_at=NULL,failure_code=NULL," +
                "lease_expires_at=clock_timestamp()+make_interval(secs=>?) WHERE id=?", settings.leaseSeconds(), id);
        return new Execution(id, job.projectId(), job.version(), job.attempt() + 1);
    }

    @Transactional
    public void heartbeat(Execution execution) {
        current(execution);
        writeCurrent(execution, "lease_expires_at=clock_timestamp()+make_interval(secs=>?)", settings.leaseSeconds());
    }

    @Transactional
    public void progress(Execution execution, Progress update) {
        Locked job = current(execution);
        if (update == null || update.repositoryId() == null || update.state() == null ||
                !Set.of("RUNNING", "READY", "FAILED", "INSUFFICIENT_EVIDENCE").contains(update.state()) ||
                update.commitSha() != null && !validSha(update.commitSha()) ||
                "READY".equals(update.state()) && update.commitSha() == null)
            throw new IllegalArgumentException("Invalid repository progress");
        if (update.failureCode() != null) checkCode(update.failureCode());
        List<Progress> progress = new ArrayList<>(job.progress());
        int index = -1;
        for (int i = 0; i < progress.size(); i++) if (progress.get(i).repositoryId().equals(update.repositoryId())) index = i;
        if (index < 0) throw new IllegalArgumentException("Unknown repository");
        Progress previous = progress.get(index);
        if (previous.commitSha() != null && !previous.commitSha().equals(update.commitSha()))
            throw new IllegalArgumentException("Commit is fixed for the job");
        progress.set(index, update);
        writeCurrent(execution, "repository_progress=CAST(? AS jsonb)", encode(progress));
    }

    @Transactional
    public void finish(Execution execution, String state, String code) {
        if (!Set.of("FAILED", "INSUFFICIENT_EVIDENCE").contains(state)) throw new IllegalArgumentException("Invalid outcome");
        checkCode(code);
        current(execution);
        writeCurrent(execution, "state=?,failure_code=?,finished_at=clock_timestamp(),lease_expires_at=NULL", state, code);
    }

    @Transactional
    public void retry(Execution execution, String code, int delaySeconds) {
        checkCode(code);
        if (delaySeconds < 0 || delaySeconds > 86400) throw new IllegalArgumentException("Invalid retry delay");
        requeue(current(execution), code, delaySeconds, false);
    }

    @Transactional
    public boolean recover(UUID id) {
        Locked job = lock(id);
        if (!"RUNNING".equals(job.state()) || job.leaseValid()) return false;
        if (!compositionMatches(job)) terminal(id, "CANCELLED", "membership_changed");
        else if (!publicMembers(job)) terminal(id, "FAILED", accessFailure(job));
        else requeue(job, "lease_expired", 0, true);
        return true;
    }

    private void requeue(Locked job, String code, int delay, boolean recovery) {
        Execution execution = new Execution(job.id(), job.projectId(), job.version(), job.attempt());
        if (job.attempt() >= settings.maxAttempts()) {
            if (recovery) terminal(job.id(), "FAILED", "attempts_exhausted");
            else writeCurrent(execution, "state='FAILED',failure_code='attempts_exhausted',finished_at=clock_timestamp(),lease_expires_at=NULL");
            return;
        }
        List<Progress> reset = job.progress().stream().map(p -> new Progress(p.repositoryId(), "QUEUED", p.commitSha(), null)).toList();
        String changes = "state='QUEUED',lease_expires_at=NULL,failure_code=?," +
                "next_attempt_at=clock_timestamp()+make_interval(secs=>?),repository_progress=CAST(? AS jsonb)";
        if (recovery) db.update("UPDATE project.analysis_job SET " + changes + " WHERE id=?", code, delay, encode(reset), job.id());
        else writeCurrent(execution, changes, code, delay, encode(reset));
    }

    void writeCurrent(Execution execution, String changes, Object... values) {
        List<Object> args = new ArrayList<>(Arrays.asList(values));
        args.add(execution.jobId()); args.add(execution.attempt()); args.add(settings.maxRunSeconds());
        int updated = db.update("UPDATE project.analysis_job SET " + changes + " WHERE id=? AND state='RUNNING' " +
                "AND attempt_count=? AND lease_expires_at>clock_timestamp() " +
                "AND started_at+make_interval(secs=>?)>clock_timestamp()", args.toArray());
        if (updated != 1) throw new IllegalStateException("Stale analysis execution");
    }

    private void terminal(UUID id, String state, String code) {
        db.update("UPDATE project.analysis_job SET state=?,failure_code=?,finished_at=clock_timestamp(),lease_expires_at=NULL " +
                "WHERE id=?", state, code, id);
    }

    Locked current(Execution execution) {
        if (execution == null) throw new IllegalArgumentException("Missing execution");
        Locked job = lock(execution.jobId());
        if (!job.projectId().equals(execution.projectId()) || job.version() != execution.membershipVersion() ||
                job.attempt() != execution.attempt() || !"RUNNING".equals(job.state()) || !job.leaseValid() ||
                !compositionMatches(job) || !publicMembers(job)) throw new IllegalStateException("Stale analysis execution");
        return job;
    }

    private Locked lock(UUID id) {
        var owners = db.query("SELECT p.owner_user_id,p.id FROM project.analysis_job j " +
                "JOIN project.registered_project p ON p.id=j.project_id WHERE j.id=?",
                (rs, n) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}, id);
        if (owners.isEmpty()) throw new IllegalStateException("Analysis job unavailable");
        UUID owner = owners.getFirst()[0], project = owners.getFirst()[1];
        ProjectWriteFence.lockUser(db, owner);
        long version = ProjectWriteFence.lockProject(db, owner, project);
        // Acquire the job only after both fences and the project, matching membership/revocation writers.
        db.queryForObject("SELECT id FROM project.analysis_job WHERE id=? FOR UPDATE", UUID.class, id);
        // Read the wall clock after waiting for locks; transaction-start time can revive an expired lease.
        return db.queryForObject("SELECT membership_version,state,attempt_count," +
                "COALESCE(lease_expires_at>clock_timestamp() AND started_at+make_interval(secs=>?)>clock_timestamp(),false),next_attempt_at<=clock_timestamp()," +
                "analysis_version,repository_progress::text FROM project.analysis_job WHERE id=?", (rs, n) ->
                new Locked(id, project, rs.getLong(1), version, rs.getString(2), rs.getInt(3), rs.getBoolean(4),
                        rs.getBoolean(5), rs.getString(6), decode(rs.getString(7)), members(project)), settings.maxRunSeconds(), id);
    }

    private List<Member> members(UUID project) {
        return db.query("SELECT id,github_repository_id,visibility,access_state FROM project.project_repository " +
                "WHERE project_id=? AND removed_at IS NULL ORDER BY id", (rs, n) ->
                new Member(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)), project);
    }

    private boolean compositionMatches(Locked job) {
        Set<UUID> ids = new HashSet<>();
        for (Progress p : job.progress()) if (p.repositoryId() == null || !ids.add(p.repositoryId())) return false;
        return job.version() == job.currentVersion() && !ids.isEmpty() && ids.size() <= 20 &&
                ids.equals(new HashSet<>(job.members().stream().map(Member::id).toList()));
    }

    private boolean publicMembers(Locked job) {
        return job.members().stream().allMatch(m -> "PUBLIC".equals(m.visibility()) && "ACTIVE".equals(m.accessState()));
    }

    private String accessFailure(Locked job) {
        return job.members().stream().anyMatch(m -> !"PUBLIC".equals(m.visibility()))
                ? "private_analysis_not_supported" : "repository_access_unavailable";
    }

    private List<Progress> decode(String value) {
        try { return json.readValue(value, new TypeReference<List<Progress>>() {}); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored progress"); }
    }

    String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid analysis data"); }
    }

    static boolean validSha(String sha) { return sha != null && sha.matches("[0-9a-f]{40}"); }
    private static void checkCode(String code) {
        if (code == null || !code.matches("[a-z][a-z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid failure code");
    }
    private static void checkLimit(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid batch size");
    }
}
