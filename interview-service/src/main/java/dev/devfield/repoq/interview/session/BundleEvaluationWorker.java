package dev.devfield.repoq.interview.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = {"devfield.interview.sessions.enabled", "devfield.interview.follow-up.enabled",
        "devfield.interview.evaluation.enabled"}, havingValue = "true")
class BundleEvaluationWorker {
    private static final int MAX_ATTEMPTS = 3;
    static final int MAX_FIELD_LENGTH = 400;
    static final List<String> FEEDBACK_FIELDS = List.of("strengths", "gaps", "improvements", "exampleAnswer",
            "keyPoints", "limitations");
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private record Pending(int attempt, String topic, UUID evidence) {}
    private record Job(InterviewAnswers.Target target, UUID bundle) {}
    private record Claim(Job job, int attempt, OffsetDateTime leaseUntil, FirstQuestionGenerator.Input fact,
                         List<InterviewSessions.SavedAnswer> turns) {}

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final InterviewSessions sessions;
    private final InterviewAnswers answers;
    private final OllamaFirstQuestionGenerator ollama;
    private final boolean mockEnabled;

    BundleEvaluationWorker(JdbcTemplate db, TransactionTemplate tx, InterviewSessions sessions,
                           InterviewAnswers answers, OllamaFirstQuestionGenerator ollama,
                   @Value("${devfield.interview.mock.enabled:false}") boolean mockEnabled) {
        this.db = db;
        this.tx = tx;
        this.sessions = sessions;
        this.answers = answers;
        this.ollama = ollama;
        this.mockEnabled = mockEnabled;
    }

    @Scheduled(fixedDelayString = "${devfield.interview.evaluation.poll-ms:1000}",
            initialDelayString = "${devfield.interview.evaluation.poll-ms:1000}")
    public void poll() {
        var due = db.query("""
                SELECT s.id,s.owner_user_id,s.project_id,e.bundle_id
                FROM interview.bundle_evaluation e JOIN interview.interview_session s ON s.id=e.session_id
                LEFT JOIN interview.mock_round m ON m.session_id=s.id
                JOIN interview.interview_write_fence u ON u.scope='USER' AND u.owner_user_id=s.owner_user_id AND u.state='ACTIVE'
                JOIN interview.interview_write_fence p ON p.scope='PROJECT' AND p.project_id=s.project_id
                    AND p.owner_user_id=s.owner_user_id AND p.state='ACTIVE'
                WHERE s.lifecycle='OPEN' AND e.state IN ('QUEUED','RUNNING') AND e.due_at<=clock_timestamp()
                    AND ((s.mode='FREE' AND s.state='ACTIVE' AND e.bundle_id=(
                        SELECT id FROM interview.question_bundle WHERE session_id=s.id ORDER BY sequence DESC LIMIT 1))
                        OR (? AND m.session_id IS NOT NULL AND s.state='EVALUATING'))
                ORDER BY e.due_at,e.session_id LIMIT 1
                """, (rs, row) -> new Job(new InterviewAnswers.Target(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)), rs.getObject(4, UUID.class)), mockEnabled);
        if (due.isEmpty()) return;
        Claim claim = tx.execute(status -> claim(due.getFirst()));
        if (claim == null) return;

        JsonNode result;
        try {
            result = validate(ollama.evaluate(claim.fact(), claim.turns()), claim.fact().evidenceId());
        } catch (FirstQuestionGenerator.Retryable retryable) {
            tx.executeWithoutResult(status -> settle(claim, null, claim.attempt() < MAX_ATTEMPTS));
            return;
        }
        tx.executeWithoutResult(status -> settle(claim, result, false));
    }

    private Claim claim(Job job) {
        if (!lockActive(job)) return null;
        var target = job.target();
        UUID id = target.session();
        var candidates = db.query("""
                SELECT e.attempt,q.topic_key,q.evidence_id
                FROM interview.bundle_evaluation e JOIN interview.question_turn q
                    ON q.session_id=e.session_id AND q.id=e.closing_question_id
                JOIN interview.user_answer a ON a.session_id=e.session_id AND a.question_id=e.closing_question_id
                WHERE e.bundle_id=? AND a.state='STOPPED' AND e.state IN ('QUEUED','RUNNING')
                    AND e.due_at<=clock_timestamp() AND (e.lease_until IS NULL OR e.lease_until<=clock_timestamp())
                FOR UPDATE OF e
                """, (rs, row) -> new Pending(rs.getInt(1) + 1, rs.getString(2), rs.getObject(3, UUID.class)), job.bundle());
        if (candidates.isEmpty()) return null;
        var pending = candidates.getFirst();
        var session = sessions.find(target.owner(), id).orElseThrow();
        var snapshot = session.snapshot();
        var evidence = snapshot.evidenceReferences().stream()
                .filter(item -> pending.evidence().toString().equals(item.id()) && "CODE_FACT".equals(item.claimType()))
                .findFirst();
        var turns = session.managedMock() ? db.query("""
                SELECT q.id,q.question_text,a.answer_text FROM interview.question_turn q
                JOIN interview.user_answer a ON a.session_id=q.session_id AND a.question_id=q.id
                WHERE q.bundle_id=? ORDER BY q.position
                """, (rs, row) -> new InterviewSessions.SavedAnswer(rs.getObject(1, UUID.class),
                rs.getString(2), rs.getString(3)), job.bundle()) : session.answers();
        if (pending.attempt() > MAX_ATTEMPTS || !session.managedMock() && !"QUESTIONS_COMPLETE".equals(session.answerProgress())
                || turns.isEmpty() || evidence.isEmpty() || snapshot.topics().stream().noneMatch(item ->
                        item.key().equals(pending.topic()) && pending.evidence().toString().equals(item.primaryEvidenceId()))) {
            db.update("UPDATE interview.bundle_evaluation SET state='FAILED',lease_until=NULL WHERE bundle_id=?", job.bundle());
            return null;
        }

        int leaseSeconds = ollama.longLeaseSeconds();
        var leaseUntil = db.queryForObject("""
                UPDATE interview.bundle_evaluation SET state='RUNNING',attempt=?,
                    lease_until=clock_timestamp()+make_interval(secs=>?),due_at=clock_timestamp()+make_interval(secs=>?)
                WHERE bundle_id=? RETURNING lease_until
                """, OffsetDateTime.class, pending.attempt(), leaseSeconds, leaseSeconds, job.bundle());
        return new Claim(job, pending.attempt(), leaseUntil,
                new FirstQuestionGenerator.Input(pending.topic(), pending.evidence(), evidence.orElseThrow().summary()),
                turns);
    }

    private void settle(Claim claim, JsonNode result, boolean retry) {
        if (!lockActive(claim.job())) return;
        String state = result != null ? "READY" : retry ? "QUEUED" : "FAILED";
        int updated = db.update("""
                UPDATE interview.bundle_evaluation SET state=?,result=CAST(? AS jsonb),lease_until=NULL,
                    due_at=clock_timestamp()+make_interval(secs=>?)
                WHERE bundle_id=? AND state='RUNNING' AND attempt=? AND lease_until=? AND lease_until>clock_timestamp()
                """, state, result == null ? null : result.toString(), retry ? 2 << (claim.attempt() - 1) : 0,
                claim.job().bundle(), claim.attempt(), claim.leaseUntil());
        if (updated == 1 && result != null) {
            UUID session = claim.job().target().session();
            db.update("""
                    INSERT INTO interview.mock_job(session_id,kind)
                    SELECT m.session_id,'SUMMARY' FROM interview.mock_round m WHERE m.session_id=?
                      AND 5=(SELECT count(*) FROM interview.bundle_evaluation e
                             WHERE e.session_id=m.session_id AND e.state='READY')
                    ON CONFLICT (session_id,kind) DO NOTHING
                    """, session);
        }
    }

    private boolean lockActive(Job job) {
        try {
            String state = answers.lockSession(job.target());
            return "EVALUATING".equals(state) || "ACTIVE".equals(state)
                    && job.bundle().equals(answers.currentBundle(job.target().session()));
        } catch (InterviewSessions.WriteBlocked | InterviewSessions.DeletionConflict blocked) {
            return false;
        }
    }

    private static JsonNode validate(String raw, UUID evidenceId) {
        if (raw == null || raw.length() > OllamaFirstQuestionGenerator.MAX_RESPONSE_BYTES) return null;
        try {
            JsonNode result = JSON.readTree(raw);
            if (result == null || !result.isObject() || result.size() != FEEDBACK_FIELDS.size() + 1
                    || !evidenceId.toString().equals(result.path("evidenceId").textValue())) return null;
            for (String field : FEEDBACK_FIELDS) {
                String value = result.path(field).textValue();
                if (value == null || value.isBlank() || value.length() > MAX_FIELD_LENGTH
                        || value.codePoints().anyMatch(cp -> Character.isISOControl(cp)
                                || cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE)) return null;
            }
            return result;
        } catch (JsonProcessingException invalid) {
            return null;
        }
    }
}
