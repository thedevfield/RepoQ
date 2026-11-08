package dev.devfield.repoq.interview.session;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class InterviewLifecycle {
    enum Action { PAUSE, RESUME, END }
    enum Outcome { OK, NOT_FOUND, CONFLICT }

    record Result(Outcome outcome, String status) {}

    private static final List<String> GENERATION_TABLES = List.of("first_question_job", "user_answer", "bundle_evaluation");

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final InterviewAnswers answers;

    InterviewLifecycle(JdbcTemplate db, TransactionTemplate tx, InterviewAnswers answers) {
        this.db = db;
        this.tx = tx;
        this.answers = answers;
    }

    boolean delete(UUID owner, UUID session, boolean allowMock, boolean confirmAbandon) {
        return Boolean.TRUE.equals(tx.execute(transaction -> {
            var target = answers.findTarget(owner, session, allowMock);
            if (target.isEmpty() || answers.lockSession(target.get()) == null) return false;
            var mode = db.queryForObject("SELECT mode FROM interview.interview_session WHERE id=?", String.class, session);
            if ("MOCK".equals(mode) && !confirmAbandon && !Boolean.TRUE.equals(db.queryForObject("""
                    SELECT state='COMPLETED' FROM interview.interview_session WHERE id=?
                    """, Boolean.class, session))) throw new InterviewSessions.DeletionConflict();
            return db.update("""
                    DELETE FROM interview.interview_session
                    WHERE id=? AND owner_user_id=? AND project_id=? AND mode=?
                    """, session, owner, target.get().project(), mode) == 1;
        }));
    }

    Result change(UUID owner, UUID session, Action action, boolean allowMock) {
        return tx.execute(transaction -> {
            var target = answers.findTarget(owner, session, allowMock);
            if (target.isEmpty()) return new Result(Outcome.NOT_FOUND, null);
            String current = answers.lockSession(target.get());
            if (current == null) return new Result(Outcome.NOT_FOUND, null);
            if ("COMPLETED".equals(current) || action == Action.END && Boolean.TRUE.equals(db.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM interview.mock_round WHERE session_id=?)
                    """, Boolean.class, session))) return new Result(Outcome.CONFLICT, current);
            if ("ENDED".equals(current)) {
                return new Result(action == Action.END ? Outcome.OK : Outcome.CONFLICT, current);
            }
            boolean paused = "PAUSED".equals(current);
            if (action == Action.PAUSE && paused || action == Action.RESUME && !paused) {
                if (action == Action.RESUME && allowMock) retryMockWork(session, current);
                return new Result(Outcome.OK, current);
            }

            String lifecycle = switch (action) {
                case PAUSE -> "PAUSED";
                case RESUME -> "OPEN";
                case END -> "ENDED";
            };
            if (action != Action.RESUME) {
                boolean ending = action == Action.END;
                for (String table : GENERATION_TABLES) {
                    db.update("""
                            UPDATE interview.%s SET state=?,lease_until=NULL,due_at=clock_timestamp()
                            WHERE session_id=? AND (state='RUNNING' OR (? AND state='QUEUED'))
                            """.formatted(table), ending ? "CANCELLED" : "QUEUED", session, ending);
                }
            }
            if (action == Action.PAUSE) {
                db.update("""
                        UPDATE interview.mock_job SET state='QUEUED',lease_until=NULL,due_at=clock_timestamp()
                        WHERE session_id=? AND state='RUNNING'
                        """, session);
            }
            String status = db.queryForObject("""
                    UPDATE interview.interview_session SET lifecycle=?,version=version+1 WHERE id=?
                    RETURNING CASE WHEN lifecycle='OPEN' THEN state ELSE lifecycle END
                    """, String.class, lifecycle, session);
            if (action == Action.RESUME && allowMock) retryMockWork(session, status);
            return new Result(Outcome.OK, status);
        });
    }

    private void retryMockWork(UUID session, String phase) {
        if ("ACTIVE".equals(phase)) {
            db.update("""
                    UPDATE interview.user_answer a SET state='QUEUED',attempt=0,due_at=clock_timestamp()
                    FROM interview.mock_round m
                    JOIN interview.question_bundle b ON b.session_id=m.session_id AND b.sequence=m.current_sequence
                    JOIN interview.question_turn q ON q.bundle_id=b.id
                    WHERE m.session_id=? AND a.session_id=m.session_id AND a.question_id=q.id AND a.state='FAILED'
                    """, session);
        } else if ("EVALUATING".equals(phase)) {
            retryMockEvaluation(session);
        }
    }

    private void retryMockEvaluation(UUID session) {
        db.update("""
                UPDATE interview.bundle_evaluation SET state='QUEUED',attempt=0,due_at=clock_timestamp()
                WHERE session_id=? AND state='FAILED'
                """, session);
        db.update("""
                UPDATE interview.mock_job SET state='QUEUED',attempt=0,due_at=clock_timestamp()
                WHERE session_id=? AND kind='SUMMARY' AND state='FAILED'
                """, session);
    }
}
