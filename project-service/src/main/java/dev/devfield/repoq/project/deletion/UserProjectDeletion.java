package dev.devfield.repoq.project.deletion;

import dev.devfield.repoq.project.api.ApiProblem;
import dev.devfield.repoq.project.data.ProjectWriteFence;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UserProjectDeletion {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    public UserProjectDeletion(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void delete(UUID owner, UUID job) {
        try {
            tx.executeWithoutResult(status -> begin(owner, job));
        } catch (DuplicateKeyException e) {
            throw conflict();
        }
        // Keep the committed USER barrier even if cleanup fails.
        tx.executeWithoutResult(status -> finish(owner, job));
    }

    private void begin(UUID owner, UUID job) {
        var user = ProjectWriteFence.lockUserFence(db, owner);
        if (!user.active()) {
            requireJob(user, job);
            return;
        }
        // Token refresh uses its own transaction and locks this row, not the USER fence.
        db.query("SELECT id FROM project.github_connection WHERE devfield_user_id=? FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), owner);
        db.update("""
                UPDATE project.project_write_fence SET state='DELETING', deletion_job_id=?, updated_at=now()
                WHERE scope='USER' AND owner_user_id=?
                """, job, owner);
        db.update("""
                UPDATE project.project_write_fence SET deletion_next_attempt_at=NULL
                WHERE scope='PROJECT' AND owner_user_id=? AND state='DELETING'
                """, owner);
        db.update("UPDATE project.registered_project SET state='DELETING', updated_at=now() WHERE owner_user_id=?",
                owner);
        db.update("""
                UPDATE project.analysis_job j SET state='CANCELLED', finished_at=clock_timestamp(), lease_expires_at=NULL
                FROM project.registered_project p WHERE j.project_id=p.id AND p.owner_user_id=?
                AND j.state IN ('QUEUED','RUNNING')
                """, owner);
    }

    private void finish(UUID owner, UUID job) {
        var user = ProjectWriteFence.lockUserFence(db, owner);
        requireJob(user, job);
        if ("COMPLETED".equals(user.state())) return;
        var projects = db.query("""
                SELECT id FROM project.registered_project WHERE owner_user_id=? ORDER BY id
                """, (rs, row) -> rs.getObject(1, UUID.class), owner);
        for (UUID project : projects) ProjectDeletions.removeProjectData(db, owner, project);
        db.update("DELETE FROM project.repository_consent WHERE owner_user_id=?", owner);
        db.update("DELETE FROM project.github_connection_attempt WHERE devfield_user_id=?", owner);
        db.update("DELETE FROM project.github_connection WHERE devfield_user_id=?", owner);
        // The USER tombstone now fences every old Project and replaces its local deletion metadata.
        db.update("DELETE FROM project.project_write_fence WHERE scope='PROJECT' AND owner_user_id=?", owner);
        db.update("""
                UPDATE project.project_write_fence SET state='COMPLETED', completed_at=now(), updated_at=now()
                WHERE scope='USER' AND owner_user_id=?
                """, owner);
    }

    private static void requireJob(ProjectWriteFence.UserFence user, UUID job) {
        if (!job.equals(user.deletionJobId())) throw conflict();
    }

    private static ApiProblem conflict() {
        return new ApiProblem(HttpStatus.CONFLICT, "deletion_conflict");
    }
}
