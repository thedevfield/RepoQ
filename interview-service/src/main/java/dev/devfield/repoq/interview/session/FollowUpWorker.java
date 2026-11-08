package dev.devfield.repoq.interview.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = {"devfield.interview.sessions.enabled", "devfield.interview.follow-up.enabled"},
        havingValue = "true")
class FollowUpWorker {
    private static final int MAX_ATTEMPTS = 3;
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    record Turn(String question, String answer) {}
    private record Job(InterviewAnswers.Target target, UUID question, UUID bundle) {}
    private record Pending(int attempt, int position, String topic, UUID evidence) {}
    private record Claim(Job job, Pending pending, FirstQuestionGenerator.Input fact, List<Turn> turns) {}
    private record Outcome(String state, String question) {}

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final InterviewSessions sessions;
    private final InterviewAnswers answers;
    private final OllamaFirstQuestionGenerator ollama;

    FollowUpWorker(JdbcTemplate db, TransactionTemplate tx, InterviewSessions sessions, InterviewAnswers answers,
                   OllamaFirstQuestionGenerator ollama) {
        this.db = db;
        this.tx = tx;
        this.sessions = sessions;
        this.answers = answers;
        this.ollama = ollama;
    }

    @Scheduled(fixedDelayString = "${devfield.interview.follow-up.poll-ms:1000}",
            initialDelayString = "${devfield.interview.follow-up.poll-ms:1000}")
    public void poll() {
        var due = db.query("""
                SELECT a.session_id,s.owner_user_id,s.project_id,a.question_id,q.bundle_id
                FROM interview.user_answer a JOIN interview.interview_session s ON s.id=a.session_id
                JOIN interview.question_turn q ON q.session_id=a.session_id AND q.id=a.question_id
                JOIN interview.interview_write_fence u ON u.scope='USER' AND u.owner_user_id=s.owner_user_id AND u.state='ACTIVE'
                JOIN interview.interview_write_fence p ON p.scope='PROJECT' AND p.project_id=s.project_id
                    AND p.owner_user_id=s.owner_user_id AND p.state='ACTIVE'
                WHERE s.mode='FREE' AND s.lifecycle='OPEN' AND s.state='ACTIVE' AND a.state IN ('QUEUED','RUNNING')
                    AND a.due_at<=clock_timestamp()
                    AND q.bundle_id=(SELECT id FROM interview.question_bundle WHERE session_id=s.id ORDER BY sequence DESC LIMIT 1)
                ORDER BY a.due_at,a.session_id LIMIT 1
                """, (rs, row) -> new Job(new InterviewAnswers.Target(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class)));
        if (due.isEmpty()) return;

        Claim claim = tx.execute(status -> claim(due.getFirst()));
        if (claim == null) return;
        Outcome outcome;
        try {
            outcome = validate(ollama.followUp(claim.fact(), claim.turns()), claim);
        } catch (FirstQuestionGenerator.Retryable retryable) {
            outcome = new Outcome(claim.pending().attempt() < MAX_ATTEMPTS ? "QUEUED" : "FAILED", null);
        }
        Outcome result = outcome;
        tx.executeWithoutResult(status -> settle(claim, result));
    }

    private Claim claim(Job job) {
        if (!lockActive(job)) return null;
        UUID session = job.target().session();
        var candidates = db.query("""
                SELECT a.attempt,q.position,q.topic_key,q.evidence_id
                FROM interview.user_answer a JOIN interview.question_turn q
                    ON q.session_id=a.session_id AND q.id=a.question_id
                WHERE a.session_id=? AND a.question_id=? AND a.state IN ('QUEUED','RUNNING')
                    AND a.due_at<=clock_timestamp() AND (a.lease_until IS NULL OR a.lease_until<=clock_timestamp())
                FOR UPDATE OF a
                """, (rs, row) -> new Pending(rs.getInt(1) + 1, rs.getInt(2), rs.getString(3),
                rs.getObject(4, UUID.class)), session, job.question());
        if (candidates.isEmpty()) return null;
        Pending pending = candidates.getFirst();
        var snapshot = sessions.find(job.target().owner(), session).orElseThrow().snapshot();
        var evidence = snapshot.evidenceReferences().stream()
                .filter(item -> pending.evidence().toString().equals(item.id()) && "CODE_FACT".equals(item.claimType()))
                .findFirst();
        if (pending.attempt() > MAX_ATTEMPTS || pending.position() >= 3 || evidence.isEmpty()
                || snapshot.topics().stream().noneMatch(item -> item.key().equals(pending.topic())
                        && pending.evidence().toString().equals(item.primaryEvidenceId()))) {
            db.update("""
                    UPDATE interview.user_answer SET state='FAILED',lease_until=NULL
                    WHERE session_id=? AND question_id=?
                    """, session, job.question());
            return null;
        }

        var turns = db.query("""
                SELECT q.question_text,a.answer_text FROM interview.question_turn q
                JOIN interview.user_answer a ON a.session_id=q.session_id AND a.question_id=q.id
                WHERE q.bundle_id=? AND q.position<=? ORDER BY q.position
                """, (rs, row) -> new Turn(rs.getString(1), rs.getString(2)), job.bundle(), pending.position());
        db.update("""
                UPDATE interview.user_answer SET state='RUNNING',attempt=?,
                    lease_until=clock_timestamp()+interval '30 seconds',due_at=clock_timestamp()+interval '30 seconds'
                WHERE session_id=? AND question_id=?
                """, pending.attempt(), session, job.question());
        return new Claim(job, pending, new FirstQuestionGenerator.Input(pending.topic(), pending.evidence(),
                evidence.orElseThrow().summary()), turns);
    }

    private void settle(Claim claim, Outcome outcome) {
        if (!lockActive(claim.job())) return;
        UUID session = claim.job().target().session();
        int updated = db.update("""
                UPDATE interview.user_answer SET state=?,lease_until=NULL,
                    due_at=clock_timestamp()+make_interval(secs=>?)
                WHERE session_id=? AND question_id=? AND state='RUNNING' AND attempt=?
                    AND lease_until>clock_timestamp()
                """, outcome.state(), "QUEUED".equals(outcome.state()) ? 2 << (claim.pending().attempt() - 1) : 0,
                session, claim.job().question(), claim.pending().attempt());
        if (updated == 1 && "STOPPED".equals(outcome.state())) {
            answers.enqueueEvaluationIfStopped(session, claim.job().question());
        }
        if (updated == 1 && "PUBLISHED".equals(outcome.state())) {
            db.update("""
                    INSERT INTO interview.question_turn(session_id,bundle_id,position,id,topic_key,evidence_id,question_text)
                    VALUES (?,?,?,?,?,?,?)
                    """, session, claim.job().bundle(), claim.pending().position() + 1, UUID.randomUUID(), claim.pending().topic(),
                    claim.pending().evidence(), outcome.question());
        }
    }

    private boolean lockActive(Job job) {
        try {
            return "ACTIVE".equals(answers.lockSession(job.target()))
                    && job.bundle().equals(answers.currentBundle(job.target().session()));
        } catch (InterviewSessions.WriteBlocked | InterviewSessions.DeletionConflict blocked) {
            return false;
        }
    }

    private static Outcome validate(String raw, Claim claim) {
        var failed = new Outcome("FAILED", null);
        if (raw == null || raw.length() > 2048) return failed;
        try {
            var result = JSON.readTree(raw);
            if (result == null || !result.isObject() || result.size() != 3
                    || !claim.fact().evidenceId().toString().equals(result.path("evidenceId").textValue())
                    || !result.path("question").isTextual()) return failed;
            String question = result.path("question").textValue().strip();
            if ("STOP".equals(result.path("action").textValue()) && question.isEmpty()) {
                return new Outcome("STOPPED", null);
            }
            if (!"FOLLOW_UP".equals(result.path("action").textValue()) || question.isEmpty() || question.length() > 500
                    || question.codePoints().anyMatch(Character::isISOControl)
                    || claim.turns().stream().anyMatch(turn -> turn.question().equalsIgnoreCase(question))) return failed;
            return new Outcome("PUBLISHED", question);
        } catch (JsonProcessingException invalid) {
            return failed;
        }
    }
}
