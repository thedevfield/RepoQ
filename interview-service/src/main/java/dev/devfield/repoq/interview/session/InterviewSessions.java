package dev.devfield.repoq.interview.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.devfield.repoq.interview.project.ProjectSnapshot;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class InterviewSessions {
    public enum Mode { FREE, MOCK }

    public record CurrentQuestion(UUID id, String text, String topicKey) {}

    public record Session(UUID id, UUID ownerUserId, UUID projectId, ProjectSnapshot snapshot,
                          Mode mode, String state, String topicKey, long version, OffsetDateTime createdAt,
                          CurrentQuestion currentQuestion) {}

    public static final class WriteBlocked extends RuntimeException {}
    public static final class DeletionConflict extends RuntimeException {}

    private record Fence(UUID id, UUID owner, String state, UUID job) {
        boolean active() { return "ACTIVE".equals(state); }
        boolean completed() { return "COMPLETED".equals(state); }
    }

    private final JdbcTemplate db;
    private final TransactionTemplate writes;
    private final TransactionTemplate deletion;
    private final ObjectMapper json = new ObjectMapper();
    private final boolean generateFirstQuestion;

    public InterviewSessions(JdbcTemplate db, PlatformTransactionManager manager,
            @Value("${devfield.interview.sessions.enabled:false}") boolean sessionsEnabled,
            @Value("${devfield.interview.first-question.fake-enabled:false}") boolean fakeEnabled) {
        this.db = db;
        generateFirstQuestion = sessionsEnabled && fakeEnabled;
        writes = new TransactionTemplate(manager);
        deletion = new TransactionTemplate(manager);
        deletion.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // Only call after the owner's active session and project access have been validated.
    public Session create(UUID owner, UUID project, Mode mode, ProjectSnapshot snapshot) {
        return create(owner, project, mode, snapshot, null);
    }

    public Session create(UUID owner, UUID project, Mode mode, ProjectSnapshot snapshot, String topicKey) {
        UUID id = UUID.randomUUID();
        UUID snapshotId = UUID.fromString(snapshot.snapshotId());
        String context = encode(snapshot);
        return writes.execute(status -> {
            lockActiveScope(owner, project);
            OffsetDateTime created = db.queryForObject("""
                    INSERT INTO interview.interview_session
                        (id, owner_user_id, project_id, mode, snapshot_id, membership_version, snapshot_context, requested_topic_key)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                    RETURNING created_at
                    """, OffsetDateTime.class, id, owner, project, mode.name(), snapshotId,
                    snapshot.membershipVersion(), context, topicKey);
            if (generateFirstQuestion && mode == Mode.FREE && topicKey != null) {
                var topics = snapshot.topics();
                var resolved = "RANDOM".equals(topicKey)
                        ? topics.get(ThreadLocalRandom.current().nextInt(topics.size()))
                        : topics.stream().filter(topic -> topic.key().equals(topicKey)).findFirst().orElseThrow();
                db.update("""
                        INSERT INTO interview.first_question_job(session_id,resolved_topic_key,evidence_id)
                        VALUES (?,?,?)
                        """, id, resolved.key(), UUID.fromString(resolved.primaryEvidenceId()));
            }
            return new Session(id, owner, project, snapshot, mode, "PREPARING", topicKey, 1, created, null);
        });
    }

    public Optional<Session> find(UUID owner, UUID sessionId) {
        return db.query("""
                SELECT s.id, s.owner_user_id, s.project_id, s.snapshot_context::text, s.mode,
                       s.state, s.requested_topic_key, s.version, s.created_at,
                       j.question_id, j.question_text, j.resolved_topic_key
                FROM interview.interview_session s
                LEFT JOIN interview.first_question_job j ON j.session_id=s.id AND j.state='READY'
                JOIN interview.interview_write_fence u ON u.scope='USER' AND u.owner_user_id=s.owner_user_id
                JOIN interview.interview_write_fence p ON p.scope='PROJECT' AND p.project_id=s.project_id
                    AND p.owner_user_id=s.owner_user_id
                WHERE s.id=? AND s.owner_user_id=? AND u.state='ACTIVE' AND p.state='ACTIVE'
                """, (rs, row) -> new Session(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), decode(rs.getString(4)), Mode.valueOf(rs.getString(5)),
                rs.getString(6), rs.getString(7), rs.getLong(8), rs.getObject(9, OffsetDateTime.class),
                rs.getObject(10, UUID.class) == null ? null : new CurrentQuestion(rs.getObject(10, UUID.class),
                        rs.getString(11), rs.getString(12))), sessionId, owner).stream().findFirst();
    }

    public void deleteProject(UUID owner, UUID project, UUID job) {
        delete(owner, Objects.requireNonNull(project), job);
    }

    public void deleteUser(UUID owner, UUID job) {
        delete(owner, null, job);
    }

    private void delete(UUID owner, UUID project, UUID job) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(job);
        try {
            deletion.executeWithoutResult(status -> {
                Fence fence = lockDeletionScope(owner, project);
                if (fence.active()) {
                    db.update("UPDATE interview.interview_write_fence SET state='DELETING', deletion_job_id=? WHERE id=?",
                            job, fence.id());
                } else {
                    requireJob(fence, job);
                }
            });
        } catch (DuplicateKeyException conflict) {
            throw new DeletionConflict();
        }

        // Commit the barrier first so a failed cleanup cannot reopen writes.
        deletion.executeWithoutResult(status -> {
            Fence fence = lockDeletionScope(owner, project);
            requireJob(fence, job);
            if (fence.completed()) return;
            if (project == null) {
                db.update("DELETE FROM interview.interview_session WHERE owner_user_id=?", owner);
            } else {
                db.update("DELETE FROM interview.interview_session WHERE owner_user_id=? AND project_id=?", owner, project);
            }
            db.update("UPDATE interview.interview_write_fence SET state='COMPLETED', completed_at=now() WHERE id=?",
                    fence.id());
        });
    }

    void lockActiveScope(UUID owner, UUID project) {
        if (!lockUser(owner).active() || !lockProject(owner, project).active()) throw new WriteBlocked();
    }

    private Fence lockDeletionScope(UUID owner, UUID project) {
        Fence user = lockUser(owner);
        return project == null ? user : lockProject(owner, project);
    }

    private Fence lockUser(UUID owner) {
        db.update("""
                INSERT INTO interview.interview_write_fence(id, scope, owner_user_id) VALUES (?, 'USER', ?)
                ON CONFLICT (owner_user_id) WHERE scope='USER' DO NOTHING
                """, UUID.randomUUID(), owner);
        return db.queryForObject("""
                SELECT id, owner_user_id, state, deletion_job_id FROM interview.interview_write_fence
                WHERE scope='USER' AND owner_user_id=? FOR UPDATE
                """, (rs, row) -> new Fence(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getObject(4, UUID.class)), owner);
    }

    private Fence lockProject(UUID owner, UUID project) {
        db.update("""
                INSERT INTO interview.interview_write_fence(id, scope, owner_user_id, project_id)
                VALUES (?, 'PROJECT', ?, ?) ON CONFLICT (project_id) WHERE scope='PROJECT' DO NOTHING
                """, UUID.randomUUID(), owner, project);
        Fence fence = db.queryForObject("""
                SELECT id, owner_user_id, state, deletion_job_id FROM interview.interview_write_fence
                WHERE scope='PROJECT' AND project_id=? FOR UPDATE
                """, (rs, row) -> new Fence(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getObject(4, UUID.class)), project);
        if (!owner.equals(fence.owner())) throw new DeletionConflict();
        return fence;
    }

    private static void requireJob(Fence fence, UUID job) {
        if (!job.equals(fence.job())) throw new DeletionConflict();
    }

    private String encode(ProjectSnapshot snapshot) {
        try {
            return json.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid interview snapshot");
        }
    }

    private ProjectSnapshot decode(String context) {
        try {
            return json.readValue(context, ProjectSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid stored interview snapshot");
        }
    }
}
