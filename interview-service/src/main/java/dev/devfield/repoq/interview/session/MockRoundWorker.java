package dev.devfield.repoq.interview.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.devfield.repoq.interview.project.ProjectSnapshot;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = {"devfield.interview.sessions.enabled", "devfield.interview.mock.enabled"}, havingValue = "true")
class MockRoundWorker {
    private static final int MAX_ATTEMPTS = 3;
    static final List<String> SUMMARY_FIELDS = List.of("strengths", "gaps", "consistency", "studyAreas", "limitations");
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    record Fact(String topicKey, String evidenceId, String codeFact) {}
    private record Question(Fact fact, String text) {}
    private record Claim(InterviewAnswers.Target target, int attempt, List<Fact> facts) {}

    private record SummaryClaim(InterviewAnswers.Target target, int attempt, OffsetDateTime leaseUntil,
                                List<BundleFeedback> feedback) {}
    record BundleFeedback(int sequence, String topicKey, JsonNode evaluation) {}

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final InterviewAnswers answers;
    private final InterviewSessions sessions;
    private final OllamaFirstQuestionGenerator ollama;

    MockRoundWorker(JdbcTemplate db, TransactionTemplate tx, InterviewAnswers answers, InterviewSessions sessions,
                    OllamaFirstQuestionGenerator ollama) {
        this.db = db;
        this.tx = tx;
        this.answers = answers;
        this.sessions = sessions;
        this.ollama = ollama;
    }

    @Scheduled(fixedDelayString = "${devfield.interview.mock.poll-ms:1000}",
            initialDelayString = "${devfield.interview.mock.poll-ms:1000}")
    public void poll() {
        var candidates = db.query("""
                SELECT s.id,s.owner_user_id,s.project_id
                FROM interview.mock_job j JOIN interview.interview_session s ON s.id=j.session_id
                JOIN interview.interview_write_fence u ON u.scope='USER' AND u.owner_user_id=s.owner_user_id AND u.state='ACTIVE'
                JOIN interview.interview_write_fence p ON p.scope='PROJECT' AND p.project_id=s.project_id
                    AND p.owner_user_id=s.owner_user_id AND p.state='ACTIVE'
                WHERE s.mode='MOCK' AND s.lifecycle='OPEN' AND s.state='PREPARING'
                    AND j.kind='PLAN' AND j.state IN ('QUEUED','RUNNING') AND j.due_at<=clock_timestamp()
                ORDER BY j.due_at,j.session_id LIMIT 1
                """, (rs, row) -> new InterviewAnswers.Target(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class)));
        if (candidates.isEmpty()) return;
        Claim claim = tx.execute(status -> claim(candidates.getFirst()));
        if (claim == null) return;

        List<Question> questions;
        try {
            questions = validate(ollama.planMock(claim.facts()), claim.facts());
        } catch (FirstQuestionGenerator.Retryable transientFailure) {
            tx.executeWithoutResult(status -> settle(claim, List.of(), claim.attempt() < MAX_ATTEMPTS));
            return;
        }
        tx.executeWithoutResult(status -> settle(claim, questions, false));
    }

    @Scheduled(fixedDelayString = "${devfield.interview.mock.poll-ms:1000}",
            initialDelayString = "${devfield.interview.mock.poll-ms:1000}")
    public void pollSummary() {
        var candidates = db.query("""
                SELECT s.id,s.owner_user_id,s.project_id
                FROM interview.mock_job j JOIN interview.interview_session s ON s.id=j.session_id
                JOIN interview.interview_write_fence u ON u.scope='USER' AND u.owner_user_id=s.owner_user_id AND u.state='ACTIVE'
                JOIN interview.interview_write_fence p ON p.scope='PROJECT' AND p.project_id=s.project_id
                    AND p.owner_user_id=s.owner_user_id AND p.state='ACTIVE'
                WHERE s.mode='MOCK' AND s.lifecycle='OPEN' AND s.state='EVALUATING'
                    AND j.kind='SUMMARY' AND j.state IN ('QUEUED','RUNNING') AND j.due_at<=clock_timestamp()
                ORDER BY j.due_at,j.session_id LIMIT 1
                """, (rs, row) -> new InterviewAnswers.Target(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class)));
        if (candidates.isEmpty()) return;
        SummaryClaim claim = tx.execute(status -> claimSummary(candidates.getFirst()));
        if (claim == null) return;
        JsonNode result;
        try {
            result = validateSummary(ollama.summarizeMock(claim.feedback()));
        } catch (FirstQuestionGenerator.Retryable transientFailure) {
            tx.executeWithoutResult(status -> settleSummary(claim, null, claim.attempt() < MAX_ATTEMPTS));
            return;
        }
        tx.executeWithoutResult(status -> settleSummary(claim, result, false));
    }

    private SummaryClaim claimSummary(InterviewAnswers.Target target) {
        if (!lockPhase(target, "EVALUATING")) return null;
        UUID id = target.session();
        var attempts = db.query("""
                SELECT attempt FROM interview.mock_job WHERE session_id=? AND kind='SUMMARY'
                    AND state IN ('QUEUED','RUNNING') AND due_at<=clock_timestamp()
                    AND (lease_until IS NULL OR lease_until<=clock_timestamp()) FOR UPDATE
                """, (rs, row) -> rs.getInt(1), id);
        if (attempts.isEmpty()) return null;
        int attempt = attempts.getFirst();
        var feedback = db.query("""
                SELECT b.sequence,j.resolved_topic_key,e.result::text
                FROM interview.bundle_evaluation e JOIN interview.question_bundle b ON b.id=e.bundle_id
                JOIN interview.first_question_job j ON j.bundle_id=b.id
                WHERE e.session_id=? AND e.state='READY' ORDER BY b.sequence
                """, (rs, row) -> new BundleFeedback(rs.getInt(1), rs.getString(2), readJson(rs.getString(3))), id);
        if (attempt >= MAX_ATTEMPTS || feedback.size() != 5) {
            db.update("UPDATE interview.mock_job SET state='FAILED',lease_until=NULL WHERE session_id=? AND kind='SUMMARY'", id);
            return null;
        }
        int leaseSeconds = ollama.longLeaseSeconds();
        var leaseUntil = db.queryForObject("""
                UPDATE interview.mock_job SET state='RUNNING',attempt=attempt+1,
                    lease_until=clock_timestamp()+make_interval(secs=>?),due_at=clock_timestamp()+make_interval(secs=>?)
                WHERE session_id=? AND kind='SUMMARY' RETURNING lease_until
                """, OffsetDateTime.class, leaseSeconds, leaseSeconds, id);
        return new SummaryClaim(target, attempt + 1, leaseUntil, feedback);
    }

    private void settleSummary(SummaryClaim claim, JsonNode result, boolean retry) {
        if (!lockPhase(claim.target(), "EVALUATING")) return;
        UUID id = claim.target().session();
        String state = result != null ? "READY" : retry ? "QUEUED" : "FAILED";
        int updated = db.update("""
                UPDATE interview.mock_job SET state=?,lease_until=NULL,due_at=clock_timestamp()+make_interval(secs=>?)
                WHERE session_id=? AND kind='SUMMARY' AND state='RUNNING' AND attempt=?
                    AND lease_until=? AND lease_until>clock_timestamp()
                """, state, retry ? 2 << (claim.attempt() - 1) : 0, id, claim.attempt(), claim.leaseUntil());
        if (updated == 1 && result != null) {
            db.update("UPDATE interview.mock_round SET summary=CAST(? AS jsonb) WHERE session_id=?", result.toString(), id);
            db.update("UPDATE interview.interview_session SET state='COMPLETED',version=version+1 WHERE id=?", id);
        }
    }

    private static JsonNode validateSummary(String raw) {
        if (raw == null) return null;
        try {
            var result = JSON.readTree(raw);
            if (result == null || !result.isObject() || result.size() != SUMMARY_FIELDS.size()) return null;
            for (String field : SUMMARY_FIELDS) {
                String value = result.path(field).textValue();
                if (value == null || value.isBlank() || value.length() > 500
                        || value.codePoints().anyMatch(cp -> Character.isISOControl(cp)
                                || cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE)) return null;
            }
            return result;
        } catch (JsonProcessingException invalid) {
            return null;
        }
    }

    private static JsonNode readJson(String value) {
        try {
            return JSON.readTree(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid stored MOCK evaluation", invalid);
        }
    }

    private Claim claim(InterviewAnswers.Target target) {
        if (!lockPhase(target, "PREPARING")) return null;
        UUID id = target.session();
        var attempts = db.query("""
                SELECT attempt FROM interview.mock_job WHERE session_id=? AND kind='PLAN'
                    AND state IN ('QUEUED','RUNNING') AND due_at<=clock_timestamp()
                    AND (lease_until IS NULL OR lease_until<=clock_timestamp()) FOR UPDATE
                """, (rs, row) -> rs.getInt(1), id);
        if (attempts.isEmpty()) return null;
        int attempt = attempts.getFirst();
        var facts = facts(sessions.find(target.owner(), id).orElseThrow().snapshot());
        if (attempt >= MAX_ATTEMPTS || facts.isEmpty()) {
            db.update("UPDATE interview.mock_job SET state='FAILED',lease_until=NULL WHERE session_id=? AND kind='PLAN'", id);
            db.update("UPDATE interview.interview_session SET state='GENERATION_FAILED',version=version+1 WHERE id=?", id);
            return null;
        }
        int leaseSeconds = ollama.longLeaseSeconds();
        db.update("""
                UPDATE interview.mock_job SET state='RUNNING',attempt=attempt+1,
                    lease_until=clock_timestamp()+make_interval(secs=>?),due_at=clock_timestamp()+make_interval(secs=>?)
                WHERE session_id=? AND kind='PLAN'
                """, leaseSeconds, leaseSeconds, id);
        return new Claim(target, attempt + 1, facts);
    }

    private void settle(Claim claim, List<Question> questions, boolean retry) {
        if (!lockPhase(claim.target(), "PREPARING")) return;
        UUID id = claim.target().session();
        String state = questions.isEmpty() ? retry ? "QUEUED" : "FAILED" : "READY";
        int updated = db.update("""
                UPDATE interview.mock_job SET state=?,lease_until=NULL,
                    due_at=clock_timestamp()+make_interval(secs=>?)
                WHERE session_id=? AND kind='PLAN' AND state='RUNNING' AND attempt=? AND lease_until>clock_timestamp()
                """, state, retry ? 2 << (claim.attempt() - 1) : 0, id, claim.attempt());
        if (updated != 1 || retry) return;

        UUID previous = null;
        int sequence = 0;
        for (Question question : questions) {
            sequence++;
            UUID bundle = sequence == 1 ? id : UUID.randomUUID();
            UUID questionId = UUID.randomUUID();
            UUID evidenceId = UUID.fromString(question.fact().evidenceId());
            if (sequence > 1) {
                db.update("""
                        INSERT INTO interview.question_bundle(id,session_id,sequence,previous_bundle_id)
                        VALUES (?,?,?,?)
                        """, bundle, id, sequence, previous);
            }
            db.update("""
                    INSERT INTO interview.first_question_job(session_id,bundle_id,resolved_topic_key,evidence_id,
                        state,question_id,question_text)
                    VALUES (?,?,?,?,'READY',?,?)
                    """, id, bundle, question.fact().topicKey(), evidenceId, questionId, question.text());
            db.update("""
                    INSERT INTO interview.question_turn(session_id,bundle_id,position,id,topic_key,evidence_id,question_text)
                    VALUES (?,?,0,?,?,?,?)
                    """, id, bundle, questionId, question.fact().topicKey(), evidenceId, question.text());
            previous = bundle;
        }
        db.update("UPDATE interview.interview_session SET state=?,version=version+1 WHERE id=?",
                questions.isEmpty() ? "GENERATION_FAILED" : "ACTIVE", id);
    }

    private boolean lockPhase(InterviewAnswers.Target target, String phase) {
        try {
            return phase.equals(answers.lockSession(target));
        } catch (InterviewSessions.WriteBlocked | InterviewSessions.DeletionConflict blocked) {
            return false;
        }
    }

    private static List<Fact> facts(ProjectSnapshot snapshot) {
        return snapshot.topics().stream().limit(16).map(topic -> snapshot.evidenceReferences().stream()
                .filter(e -> topic.primaryEvidenceId().equals(e.id()) && "CODE_FACT".equals(e.claimType()))
                .findFirst().map(e -> new Fact(topic.key(), e.id(), e.summary())).orElseThrow()).toList();
    }

    private static List<Question> validate(String raw, List<Fact> facts) {
        if (raw == null) return List.of();
        try {
            JsonNode root = JSON.readTree(raw);
            if (root == null || !root.isObject() || root.size() != 1) return List.of();
            var items = root.path("questions");
            if (!items.isArray() || items.size() != 5) return List.of();
            var seen = new HashSet<String>();
            var result = new ArrayList<Question>();
            for (var item : items) {
                if (!item.isObject() || item.size() != 3 || !item.path("question").isTextual()) return List.of();
                String question = item.path("question").textValue().strip();
                var fact = facts.stream().filter(candidate -> candidate.topicKey().equals(item.path("topicKey").textValue())
                        && candidate.evidenceId().equals(item.path("evidenceId").textValue())).findFirst();
                String key = question.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
                if (fact.isEmpty() || question.length() > 500 || key.isEmpty() || !seen.add(key)
                        || question.codePoints().anyMatch(cp -> Character.isISOControl(cp)
                                || cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE)) return List.of();
                result.add(new Question(fact.get(), question));
            }
            return result;
        } catch (JsonProcessingException invalid) {
            return List.of();
        }
    }
}
