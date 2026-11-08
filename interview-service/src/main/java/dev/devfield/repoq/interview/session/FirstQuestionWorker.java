package dev.devfield.repoq.interview.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.devfield.repoq.interview.project.ProjectSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "devfield.interview.sessions.enabled", havingValue = "true")
class FirstQuestionWorker {
    private static final int MAX_ATTEMPTS = 3;
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private record Target(UUID session, UUID owner, UUID project, UUID bundle) {}
    private record Claim(Target target, int attempt, FirstQuestionGenerator.Input input) {}
    private record Pending(int attempt, String topic, UUID evidenceId) {}

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final InterviewSessions sessions;
    private final FirstQuestionGenerator generator;

    @Autowired
    FirstQuestionWorker(JdbcTemplate db, TransactionTemplate tx, InterviewSessions sessions,
                        Optional<FirstQuestionGenerator> generator) {
        this(db, tx, sessions, generator.orElse(null));
    }

    FirstQuestionWorker(JdbcTemplate db, TransactionTemplate tx, InterviewSessions sessions, FirstQuestionGenerator generator) {
        this.db = db;
        this.tx = tx;
        this.sessions = sessions;
        this.generator = generator;
    }

    @Scheduled(fixedDelayString = "${devfield.interview.first-question.poll-ms:1000}",
            initialDelayString = "${devfield.interview.first-question.poll-ms:1000}")
    public void poll() {
        if (generator == null) return;
        List<Target> due = db.query("""
                SELECT s.id,s.owner_user_id,s.project_id,j.bundle_id
                FROM interview.first_question_job j JOIN interview.interview_session s ON s.id=j.session_id
                JOIN interview.interview_write_fence u ON u.scope='USER' AND u.owner_user_id=s.owner_user_id AND u.state='ACTIVE'
                JOIN interview.interview_write_fence p ON p.scope='PROJECT' AND p.project_id=s.project_id
                    AND p.owner_user_id=s.owner_user_id AND p.state='ACTIVE'
                WHERE s.state='PREPARING' AND j.bundle_id=(SELECT id FROM interview.question_bundle
                    WHERE session_id=s.id ORDER BY sequence DESC LIMIT 1) AND j.state IN ('QUEUED','RUNNING') AND j.due_at<=clock_timestamp()
                ORDER BY j.due_at,j.session_id LIMIT 1
                """, (rs, row) -> new Target(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class)));
        if (due.isEmpty()) return;

        Claim claim = tx.execute(status -> claim(due.getFirst()));
        if (claim == null) return;
        String question;
        try {
            question = validate(generator.generate(claim.input()), claim.input().evidenceId());
        } catch (FirstQuestionGenerator.Retryable e) {
            tx.executeWithoutResult(status -> settle(claim, null, true));
            return;
        }
        tx.executeWithoutResult(status -> settle(claim, question, false));
    }

    private Claim claim(Target target) {
        if (!lockPreparing(target)) return null;
        var eligible = db.query("""
                SELECT attempt,resolved_topic_key,evidence_id FROM interview.first_question_job
                WHERE bundle_id=? AND state IN ('QUEUED','RUNNING') AND due_at<=clock_timestamp()
                AND (lease_until IS NULL OR lease_until<=clock_timestamp()) FOR UPDATE
                """, (rs, row) -> new Pending(rs.getInt(1), rs.getString(2), rs.getObject(3, UUID.class)), target.bundle());
        if (eligible.isEmpty()) return null;
        var job = eligible.getFirst();
        int attempt = job.attempt();
        if (attempt >= MAX_ATTEMPTS) {
            fail(target);
            return null;
        }
        ProjectSnapshot snapshot;
        try {
            snapshot = sessions.find(target.owner(), target.session()).orElseThrow().snapshot();
        } catch (IllegalStateException invalidContext) {
            fail(target);
            return null;
        }
        String topic = job.topic();
        UUID evidenceId = job.evidenceId();
        var evidence = snapshot.evidenceReferences().stream()
                .filter(item -> item.id().equals(evidenceId.toString()) && "CODE_FACT".equals(item.claimType()))
                .findFirst();
        if (evidence.isEmpty() || snapshot.topics().stream().noneMatch(item ->
                item.key().equals(topic) && item.primaryEvidenceId().equals(evidenceId.toString()))) {
            fail(target);
            return null;
        }
        db.update("""
                UPDATE interview.first_question_job SET state='RUNNING',attempt=attempt+1,
                    lease_until=clock_timestamp()+interval '30 seconds',due_at=clock_timestamp()+interval '30 seconds'
                WHERE bundle_id=?
                """, target.bundle());
        List<String> history = target.bundle().equals(target.session()) ? List.of() : db.query("""
                SELECT q.question_text FROM interview.question_turn q
                JOIN interview.interview_session s ON s.id=q.session_id
                JOIN interview.question_bundle b ON b.id=q.bundle_id
                WHERE s.owner_user_id=? AND s.project_id=? AND s.mode='FREE' AND q.position=0
                ORDER BY s.created_at DESC,b.sequence DESC,b.id LIMIT 8
                """, (rs, row) -> rs.getString(1), target.owner(), target.project());
        return new Claim(target, attempt + 1,
                new FirstQuestionGenerator.Input(topic, evidenceId, evidence.get().summary(), history));
    }

    private void settle(Claim claim, String question, boolean retryable) {
        if (!lockPreparing(claim.target())) return;
        UUID session = claim.target().session();
        boolean retry = retryable && claim.attempt() < MAX_ATTEMPTS;
        String jobState = question != null ? "READY" : retry ? "QUEUED" : "FAILED";
        int updated = db.update("""
                UPDATE interview.first_question_job SET state=?,lease_until=NULL,question_id=?,question_text=?,
                    due_at=clock_timestamp()+make_interval(secs=>?)
                WHERE bundle_id=? AND state='RUNNING' AND attempt=? AND lease_until>clock_timestamp()
                """, jobState, question == null ? null : UUID.randomUUID(), question,
                retry ? 2 << (claim.attempt() - 1) : 0, claim.target().bundle(), claim.attempt());
        if (updated == 1 && !retry) {
            if (question != null) {
                db.update("""
                        INSERT INTO interview.question_turn(session_id,bundle_id,position,id,topic_key,evidence_id,question_text)
                        SELECT session_id,bundle_id,0,question_id,resolved_topic_key,evidence_id,question_text
                        FROM interview.first_question_job WHERE bundle_id=?
                        """, claim.target().bundle());
            }
            db.update("UPDATE interview.interview_session SET state=?,version=version+1 WHERE id=?",
                    question == null ? "GENERATION_FAILED" : "ACTIVE", session);
        }
    }

    private boolean lockPreparing(Target target) {
        try {
            sessions.lockActiveScope(target.owner(), target.project());
        } catch (InterviewSessions.WriteBlocked | InterviewSessions.DeletionConflict blocked) {
            return false;
        }
        return !db.query("""
                SELECT id FROM interview.interview_session
                WHERE id=? AND owner_user_id=? AND project_id=? AND mode='FREE' AND state='PREPARING'
                    AND ?=(SELECT id FROM interview.question_bundle WHERE session_id=? ORDER BY sequence DESC LIMIT 1)
                FOR UPDATE
                """, (rs, row) -> rs.getObject(1, UUID.class), target.session(), target.owner(), target.project(),
                target.bundle(), target.session()).isEmpty();
    }

    private void fail(Target target) {
        db.update("UPDATE interview.first_question_job SET state='FAILED',lease_until=NULL WHERE bundle_id=?", target.bundle());
        db.update("UPDATE interview.interview_session SET state='GENERATION_FAILED',version=version+1 WHERE id=?", target.session());
    }

    private static String validate(String raw, UUID evidenceId) {
        if (raw == null || raw.length() > 2048) return null;
        try {
            JsonNode result = JSON.readTree(raw);
            if (result == null || !result.isObject() || result.size() != 2
                    || !result.path("question").isTextual() || !result.path("evidenceId").isTextual()
                    || !evidenceId.toString().equals(result.path("evidenceId").textValue())) return null;
            String text = result.path("question").textValue().strip();
            return !text.isEmpty() && text.length() <= 500 && text.codePoints().noneMatch(Character::isISOControl) ? text : null;
        } catch (JsonProcessingException invalid) {
            return null;
        }
    }
}
