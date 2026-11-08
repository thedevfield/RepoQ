package dev.devfield.repoq.interview.session;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
class InterviewAnswers {
    record Target(UUID session, UUID owner, UUID project) {}
    private record Submission(UUID question, String answer) {}

    enum Result { ACCEPTED, CONFLICT, NOT_FOUND }

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final InterviewSessions sessions;

    InterviewAnswers(JdbcTemplate db, TransactionTemplate tx, InterviewSessions sessions) {
        this.db = db;
        this.tx = tx;
        this.sessions = sessions;
    }

    Result submit(UUID owner, UUID session, UUID question, String answer, String submissionId) {
        return tx.execute(status -> {
            var targets = db.query("""
                    SELECT project_id FROM interview.interview_session WHERE id=? AND owner_user_id=? AND mode='FREE'
                    """, (rs, row) -> new Target(session, owner, rs.getObject(1, UUID.class)), session, owner);
            if (targets.isEmpty()) return Result.NOT_FOUND;
            String state = lockSession(targets.getFirst());
            if (state == null) return Result.NOT_FOUND;

            var previous = db.query("""
                    SELECT question_id,answer_text FROM interview.user_answer WHERE session_id=? AND submission_id=?
                    """, (rs, row) -> new Submission(rs.getObject(1, UUID.class), rs.getString(2)), session, submissionId);
            if (!previous.isEmpty()) {
                return previous.getFirst().equals(new Submission(question, answer)) ? Result.ACCEPTED : Result.CONFLICT;
            }
            if (!"ACTIVE".equals(state)) return Result.CONFLICT;
            int inserted = db.update("""
                    INSERT INTO interview.user_answer(session_id,question_id,submission_id,answer_text,state)
                    SELECT session_id,id,?,?,CASE WHEN position=3 THEN 'STOPPED' ELSE 'QUEUED' END
                    FROM interview.question_turn
                    WHERE session_id=? AND id=? AND position=(
                        SELECT max(position) FROM interview.question_turn WHERE session_id=?)
                    ON CONFLICT (session_id,question_id) DO NOTHING
                    """, submissionId, answer, session, question, session);
            return inserted == 1 ? Result.ACCEPTED : Result.CONFLICT;
        });
    }

    String lockSession(Target target) {
        sessions.lockActiveScope(target.owner(), target.project());
        return db.query("""
                SELECT state FROM interview.interview_session WHERE id=? AND owner_user_id=? AND project_id=?
                    AND mode='FREE' FOR UPDATE
                """, (rs, row) -> rs.getString(1), target.session(), target.owner(), target.project())
                .stream().findFirst().orElse(null);
    }
}
