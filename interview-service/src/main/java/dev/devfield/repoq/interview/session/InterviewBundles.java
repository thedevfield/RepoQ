package dev.devfield.repoq.interview.session;

import dev.devfield.repoq.interview.project.ProjectSnapshot;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class InterviewBundles {
    enum Status { ACCEPTED, CONFLICT, NOT_FOUND, INVALID_TOPIC, UNAVAILABLE }

    record Result(Status status, UUID bundleId) {
        Result(Status status) { this(status, null); }
    }

    private record Bundle(UUID id, String topic) {}

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final InterviewSessions sessions;
    private final InterviewAnswers answers;
    private final boolean available;

    InterviewBundles(JdbcTemplate db, TransactionTemplate tx, InterviewSessions sessions, InterviewAnswers answers,
                     Optional<FirstQuestionGenerator> generator,
                     @Value("${devfield.interview.follow-up.enabled:false}") boolean followUpEnabled,
                     @Value("${devfield.interview.evaluation.enabled:false}") boolean evaluationEnabled) {
        this.db = db;
        this.tx = tx;
        this.sessions = sessions;
        this.answers = answers;
        this.available = followUpEnabled && evaluationEnabled && generator.isPresent();
    }

    Result request(UUID owner, UUID session, UUID previousBundle, String topic) {
        return tx.execute(status -> {
            var targets = db.query("""
                    SELECT project_id FROM interview.interview_session WHERE id=? AND owner_user_id=? AND mode='FREE'
                    """, (rs, row) -> new InterviewAnswers.Target(session, owner, rs.getObject(1, UUID.class)), session, owner);
            if (targets.isEmpty()) return new Result(Status.NOT_FOUND);
            String state = answers.lockSession(targets.getFirst());
            if (state == null) return new Result(Status.NOT_FOUND);

            var existing = db.query("""
                    SELECT id,requested_topic_key FROM interview.question_bundle WHERE session_id=? AND previous_bundle_id=?
                    """, (rs, row) -> new Bundle(rs.getObject(1, UUID.class), rs.getString(2)), session, previousBundle);
            if (!existing.isEmpty()) {
                Bundle bundle = existing.getFirst();
                return topic.equals(bundle.topic()) ? new Result(Status.ACCEPTED, bundle.id()) : new Result(Status.CONFLICT);
            }
            if (!"ACTIVE".equals(state) || !previousBundle.equals(answers.currentBundle(session))) {
                return new Result(Status.CONFLICT);
            }
            var detail = sessions.find(owner, session).orElseThrow();
            if (!"READY".equals(detail.evaluationStatus())) return new Result(Status.CONFLICT);
            if (!available) return new Result(Status.UNAVAILABLE);

            var eligible = detail.snapshot().topics().stream()
                    .filter(candidate -> "RANDOM".equals(topic) || topic.equals(candidate.key()))
                    .filter(candidate -> hasCodeFact(detail.snapshot(), candidate)).toList();
            if (eligible.isEmpty()) return new Result(Status.INVALID_TOPIC);
            var resolved = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
            UUID bundle = UUID.randomUUID();
            db.update("""
                    INSERT INTO interview.question_bundle(id,session_id,sequence,previous_bundle_id,requested_topic_key)
                    SELECT ?,session_id,sequence+1,id,? FROM interview.question_bundle WHERE id=?
                    """, bundle, topic, previousBundle);
            sessions.enqueueMainQuestion(session, bundle, resolved);
            db.update("UPDATE interview.interview_session SET state='PREPARING',version=version+1 WHERE id=?", session);
            return new Result(Status.ACCEPTED, bundle);
        });
    }

    private static boolean hasCodeFact(ProjectSnapshot snapshot, ProjectSnapshot.Topic topic) {
        return snapshot.evidenceReferences().stream().anyMatch(item ->
                topic.primaryEvidenceId().equals(item.id()) && "CODE_FACT".equals(item.claimType()));
    }
}
