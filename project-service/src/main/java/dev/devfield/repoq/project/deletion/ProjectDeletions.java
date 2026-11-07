package dev.devfield.repoq.project.deletion;

import dev.devfield.repoq.project.api.ApiProblem;
import dev.devfield.repoq.project.data.ProjectWriteFence;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ProjectDeletions {
    public record Pending(UUID id, String projectStatus, UUID deletionJobId) {}
    private record Target(UUID owner, UUID project) {}
    private record Fence(String state, UUID job, long attempt, boolean due) {}
    private record Attempt(InterviewDeletionClient.Command command, long number) {}

    private static final Logger log = LoggerFactory.getLogger(ProjectDeletions.class);
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final InterviewDeletionClient interview;
    private final int retrySeconds;

    public ProjectDeletions(JdbcTemplate db, TransactionTemplate tx, InterviewDeletionClient interview,
                            @Value("${devfield.project.deletion.retry-seconds}") int retrySeconds) {
        this.db = db;
        this.tx = tx;
        this.interview = interview;
        if (retrySeconds < 1 || retrySeconds > 86400) throw new IllegalArgumentException("Invalid deletion retry interval");
        this.retrySeconds = retrySeconds;
    }

    // A null result means the owner's deletion already completed.
    public Pending request(UUID owner, UUID project) {
        var target = new Target(owner, project);
        return tx.execute(status -> {
            ProjectWriteFence.lockUser(db, owner);
            Fence fence = lockFence(target);
            if (fence == null) throw new ApiProblem(HttpStatus.NOT_FOUND, "project_not_found");
            if ("COMPLETED".equals(fence.state())) return null;
            if ("DELETING".equals(fence.state())) return pending(project, fence.job());
            UUID job = UUID.randomUUID();
            var keys = db.query("""
                    SELECT create_request_key FROM project.registered_project
                    WHERE id=? AND owner_user_id=? AND state='ACTIVE' FOR UPDATE
                    """, (rs, row) -> rs.getString(1), project, owner);
            if (keys.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "project_not_found");
            db.update("UPDATE project.registered_project SET state='DELETING', updated_at=now() WHERE id=? AND owner_user_id=?",
                    project, owner);
            db.update("""
                    UPDATE project.project_write_fence SET state='DELETING', deletion_job_id=?,
                        deletion_next_attempt_at=clock_timestamp(), deleted_create_key_hash=sha256(convert_to(?, 'UTF8')),
                        updated_at=now()
                    WHERE scope='PROJECT' AND project_id=? AND owner_user_id=?
                    """, job, keys.getFirst(), project, owner);
            db.update("""
                    UPDATE project.analysis_job SET state='CANCELLED', finished_at=clock_timestamp(), lease_expires_at=NULL,
                        repository_progress=(SELECT COALESCE(jsonb_agg(jsonb_set(x,'{state}','"CANCELLED"'::jsonb)),'[]'::jsonb)
                                             FROM jsonb_array_elements(repository_progress) x)
                    WHERE project_id=? AND state IN ('QUEUED','RUNNING')
                    """, project);
            return pending(project, job);
        });
    }

    @Scheduled(fixedDelayString = "${devfield.project.deletion.poll-interval:PT2S}",
            initialDelayString = "${devfield.project.deletion.poll-interval:PT2S}")
    public void poll() {
        try {
            var due = db.query("""
                    SELECT owner_user_id, project_id FROM project.project_write_fence
                    WHERE scope='PROJECT' AND state='DELETING' AND deletion_next_attempt_at<=clock_timestamp()
                    ORDER BY deletion_next_attempt_at, project_id LIMIT 1
                    """, (rs, row) -> new Target(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)));
            if (!due.isEmpty()) process(due.getFirst());
        } catch (RuntimeException failure) {
            log.warn("Project deletion polling deferred");
        }
    }

    private void process(Target target) {
        Attempt attempt = tx.execute(status -> {
            ProjectWriteFence.lockUser(db, target.owner());
            Fence fence = lockFence(target);
            if (fence == null || !"DELETING".equals(fence.state()) || !fence.due()) return null;
            long number = fence.attempt() + 1;
            db.update("""
                    UPDATE project.project_write_fence SET deletion_attempt_count=?,
                        deletion_next_attempt_at=clock_timestamp()+make_interval(secs=>?), updated_at=now()
                    WHERE scope='PROJECT' AND project_id=? AND owner_user_id=?
                    """, number, interview.timeoutSeconds() + retrySeconds, target.project(), target.owner());
            return new Attempt(new InterviewDeletionClient.Command(target.project(), target.owner(), fence.job()), number);
        });
        if (attempt == null) return;

        var result = interview.delete(attempt.command());
        if (result != InterviewDeletionClient.Result.COMPLETE) {
            retry(attempt, result.name().toLowerCase(java.util.Locale.ROOT));
            return;
        }
        try {
            tx.executeWithoutResult(status -> complete(target, attempt));
        } catch (DataAccessException | TransactionException failure) {
            retry(attempt, "project_cleanup_failed");
        }
    }

    private void complete(Target target, Attempt attempt) {
        ProjectWriteFence.lockUser(db, target.owner());
        if (!ownsAttempt(lockFence(target), attempt)) return;
        UUID project = target.project();
        UUID owner = target.owner();
        int matched = db.update("""
                UPDATE project.registered_project SET latest_ready_snapshot_id=NULL
                WHERE id=? AND owner_user_id=? AND state='DELETING'
                """, project, owner);
        if (matched != 1) throw new IllegalStateException("Project deletion target unavailable");
        db.update("DELETE FROM project.available_topic WHERE snapshot_id IN (SELECT id FROM project.analysis_snapshot WHERE project_id=?)", project);
        db.update("DELETE FROM project.evidence_reference WHERE snapshot_id IN (SELECT id FROM project.analysis_snapshot WHERE project_id=?)", project);
        db.update("DELETE FROM project.snapshot_member WHERE project_id=?", project);
        db.update("DELETE FROM project.analysis_snapshot WHERE project_id=?", project);
        db.update("DELETE FROM project.analysis_job WHERE project_id=?", project);
        db.update("DELETE FROM project.project_repository WHERE project_id=? AND owner_user_id=?", project, owner);
        db.update("DELETE FROM project.registered_project WHERE id=? AND owner_user_id=?", project, owner);
        db.update("""
                UPDATE project.project_write_fence SET state='COMPLETED', completed_at=now(), updated_at=now(),
                    deletion_next_attempt_at=NULL, deletion_failure_code=NULL
                WHERE scope='PROJECT' AND project_id=? AND owner_user_id=?
                """, project, owner);
    }

    private void retry(Attempt attempt, String code) {
        var command = attempt.command();
        tx.executeWithoutResult(status -> {
            ProjectWriteFence.lockUser(db, command.ownerUserId());
            var target = new Target(command.ownerUserId(), command.projectId());
            if (!ownsAttempt(lockFence(target), attempt)) return;
            db.update("""
                    UPDATE project.project_write_fence SET deletion_failure_code=?, updated_at=now(),
                        deletion_next_attempt_at=clock_timestamp()+make_interval(secs=>?)
                    WHERE scope='PROJECT' AND project_id=? AND owner_user_id=?
                    """, code, retrySeconds, target.project(), target.owner());
        });
    }

    private Fence lockFence(Target target) {
        return db.query("""
                SELECT state, deletion_job_id, deletion_attempt_count,
                       COALESCE(deletion_next_attempt_at<=clock_timestamp(), false)
                FROM project.project_write_fence
                WHERE scope='PROJECT' AND owner_user_id=? AND project_id=? FOR UPDATE
                """, (rs, row) -> new Fence(rs.getString(1), rs.getObject(2, UUID.class),
                rs.getLong(3), rs.getBoolean(4)), target.owner(), target.project()).stream().findFirst().orElse(null);
    }

    private static boolean ownsAttempt(Fence fence, Attempt attempt) {
        return fence != null && "DELETING".equals(fence.state()) &&
                attempt.command().deletionJobId().equals(fence.job()) && attempt.number() == fence.attempt();
    }

    private static Pending pending(UUID project, UUID job) { return new Pending(project, "DELETING", job); }
}
