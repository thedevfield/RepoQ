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

    Result change(UUID owner, UUID session, Action action) {
        return tx.execute(transaction -> {
            var target = answers.findTarget(owner, session);
            if (target.isEmpty()) return new Result(Outcome.NOT_FOUND, null);
            String current = answers.lockSession(target.get());
            if (current == null) return new Result(Outcome.NOT_FOUND, null);
            if ("ENDED".equals(current)) {
                return new Result(action == Action.END ? Outcome.OK : Outcome.CONFLICT, current);
            }
            boolean paused = "PAUSED".equals(current);
            if (action == Action.PAUSE && paused || action == Action.RESUME && !paused) {
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
            String status = db.queryForObject("""
                    UPDATE interview.interview_session SET lifecycle=?,version=version+1 WHERE id=?
                    RETURNING CASE WHEN lifecycle='OPEN' THEN state ELSE lifecycle END
                    """, String.class, lifecycle, session);
            return new Result(Outcome.OK, status);
        });
    }
}
