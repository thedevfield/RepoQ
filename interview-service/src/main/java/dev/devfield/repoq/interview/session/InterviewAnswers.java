package dev.devfield.repoq.interview.session;

import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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

    private final boolean evaluationEnabled;

    InterviewAnswers(JdbcTemplate db, TransactionTemplate tx, InterviewSessions sessions,
                     @Value("${devfield.interview.evaluation.enabled:false}") boolean evaluationEnabled) {
        this.db = db;
        this.tx = tx;
        this.sessions = sessions;
        this.evaluationEnabled = evaluationEnabled;
    }

    Result submit(UUID owner, UUID session, UUID question, String answer, String submissionId) {
        return tx.execute(status -> {
            var target = findTarget(owner, session);
            if (target.isEmpty()) return Result.NOT_FOUND;
            String state = lockSession(target.get());
            if (state == null) return Result.NOT_FOUND;

            var previous = db.query("""
                    SELECT question_id,answer_text FROM interview.user_answer WHERE session_id=? AND submission_id=?
                    """, (rs, row) -> new Submission(rs.getObject(1, UUID.class), rs.getString(2)), session, submissionId);
            if (!previous.isEmpty()) {
                return previous.getFirst().equals(new Submission(question, answer)) ? Result.ACCEPTED : Result.CONFLICT;
            }
            if (!"ACTIVE".equals(state)) return Result.CONFLICT;
            UUID bundle = currentBundle(session);
            int inserted = db.update("""
                    INSERT INTO interview.user_answer(session_id,question_id,submission_id,answer_text,state)
                    SELECT session_id,id,?,?,CASE WHEN position=3 THEN 'STOPPED' ELSE 'QUEUED' END
                    FROM interview.question_turn
                    WHERE bundle_id=? AND id=? AND position=(
                        SELECT max(position) FROM interview.question_turn WHERE bundle_id=?)
                    ON CONFLICT (session_id,question_id) DO NOTHING
                    """, submissionId, answer, bundle, question, bundle);
            if (inserted == 0) return Result.CONFLICT;
            enqueueEvaluationIfStopped(session, question);
            return Result.ACCEPTED;
        });
    }

    void enqueueEvaluationIfStopped(UUID session, UUID question) {
        if (!evaluationEnabled) return;
        db.update("""
                INSERT INTO interview.bundle_evaluation(session_id,bundle_id,closing_question_id)
                SELECT a.session_id,q.bundle_id,a.question_id FROM interview.user_answer a
                JOIN interview.question_turn q ON q.session_id=a.session_id AND q.id=a.question_id
                WHERE a.session_id=? AND a.question_id=? AND a.state='STOPPED'
                ON CONFLICT (bundle_id) DO NOTHING
                """, session, question);
    }

    UUID currentBundle(UUID session) {
        return db.queryForObject("""
                SELECT id FROM interview.question_bundle WHERE session_id=? ORDER BY sequence DESC LIMIT 1
                """, UUID.class, session);
    }

    Optional<Target> findTarget(UUID owner, UUID session) {
        return db.query("""
                SELECT project_id FROM interview.interview_session WHERE id=? AND owner_user_id=? AND mode='FREE'
                """, (rs, row) -> new Target(session, owner, rs.getObject(1, UUID.class)), session, owner)
                .stream().findFirst();
    }

    String lockSession(Target target) {
        sessions.lockActiveScope(target.owner(), target.project());
        return db.query("""
                SELECT CASE WHEN lifecycle='OPEN' THEN state ELSE lifecycle END
                FROM interview.interview_session WHERE id=? AND owner_user_id=? AND project_id=?
                    AND mode='FREE' FOR UPDATE
                """, (rs, row) -> rs.getString(1), target.session(), target.owner(), target.project())
                .stream().findFirst().orElse(null);
    }
}
