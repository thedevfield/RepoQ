package dev.devfield.repoq.interview.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.devfield.repoq.interview.project.ProjectSnapshotClient;
import dev.devfield.repoq.interview.security.InterviewAuthentication;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@ConditionalOnProperty(name = "devfield.interview.sessions.enabled", havingValue = "true")
public class InterviewSessionController {
    public record Start(UUID projectId, InterviewSessions.Mode mode, String topicKey) {}
    public record Answer(UUID questionId, String answer, String submissionId) {}
    public record Detail(UUID interviewId, InterviewSessions.Mode mode, String status, String snapshotId,
                         String topicKey, int completedBundles,
                         @JsonInclude(JsonInclude.Include.NON_NULL) InterviewSessions.CurrentQuestion currentQuestion,
                         @JsonInclude(JsonInclude.Include.NON_NULL) List<InterviewSessions.SavedAnswer> answers,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String answerProgress,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String evaluationStatus,
                         @JsonInclude(JsonInclude.Include.NON_NULL) InterviewSessions.Evaluation evaluation) {
        static Detail from(InterviewSessions.Session session, boolean followUpEnabled) {
            boolean showAnswers = followUpEnabled || !session.answers().isEmpty();
            return new Detail(session.id(), session.mode(), session.state(), session.snapshot().snapshotId(),
                    session.topicKey(), "READY".equals(session.evaluationStatus()) ? 1 : 0,
                    session.currentQuestion(), showAnswers ? session.answers() : null,
                    showAnswers ? session.answerProgress() : null, session.evaluationStatus(), session.evaluation());
        }
    }

    private final InterviewAuthentication auth;
    private final ProjectSnapshotClient snapshots;
    private final InterviewSessions sessions;
    private final InterviewAnswers answers;
    private final boolean followUpEnabled;

    public InterviewSessionController(InterviewAuthentication auth, ProjectSnapshotClient snapshots,
                                      InterviewSessions sessions, InterviewAnswers answers,
                                      @Value("${devfield.interview.follow-up.enabled:false}") boolean followUpEnabled) {
        this.auth = auth;
        this.snapshots = snapshots;
        this.sessions = sessions;
        this.answers = answers;
        this.followUpEnabled = followUpEnabled;
    }

    @PostMapping("/api/interviews")
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt, @RequestBody Start request) {
        if (request.projectId() == null || request.mode() == null) return error(400, "invalid_request");
        if (request.mode() != InterviewSessions.Mode.FREE) return error(400, "interview_mode_not_supported");
        String topic = request.topicKey();
        if (topic == null || !topic.matches("[A-Z][A-Z0-9_]{0,63}")) return error(400, "invalid_topic");

        // X-01 verifies AUTH-15 and runs before taking any Interview database locks.
        var snapshot = snapshots.fetch(request.projectId(), "Bearer " + jwt.getTokenValue());
        if (!"RANDOM".equals(topic) && snapshot.topics().stream().noneMatch(candidate -> topic.equals(candidate.key())))
            return error(400, "invalid_topic");

        var session = sessions.create(UUID.fromString(jwt.getSubject()), request.projectId(), request.mode(), snapshot, topic);
        return ResponseEntity.created(URI.create("/api/interviews/" + session.id())).cacheControl(CacheControl.noStore())
                .body(Detail.from(session, followUpEnabled));
    }

    @GetMapping("/api/interviews/{id}")
    public ResponseEntity<?> find(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        auth.verify("Bearer " + jwt.getTokenValue());
        var session = sessions.find(UUID.fromString(jwt.getSubject()), id)
                .filter(found -> found.mode() == InterviewSessions.Mode.FREE);
        if (session.isEmpty()) return error(404, "interview_not_found");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Detail.from(session.get(), followUpEnabled));
    }

    @PostMapping("/api/interviews/{id}/answers")
    public ResponseEntity<?> answer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody Answer body) {
        if (!followUpEnabled) return error(403, "interview_unavailable");
        auth.verify("Bearer " + jwt.getTokenValue());
        if (body.questionId() == null || body.answer() == null || body.answer().isBlank()
                || body.answer().length() > 4000 || body.answer().indexOf(0) >= 0
                || body.submissionId() == null || !body.submissionId().matches("[A-Za-z0-9_-]{1,128}")) {
            return error(400, "invalid_request");
        }
        return switch (answers.submit(UUID.fromString(jwt.getSubject()), id, body.questionId(), body.answer(),
                body.submissionId())) {
            case ACCEPTED -> ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                    .body(Map.of("status", "ACCEPTED"));
            case CONFLICT -> error(409, "answer_conflict");
            case NOT_FOUND -> error(404, "interview_not_found");
        };
    }

    @ExceptionHandler(InterviewAuthentication.Rejected.class)
    ResponseEntity<?> authFailure(InterviewAuthentication.Rejected rejected) {
        return switch (rejected.failure()) {
            case INVALID_SESSION -> error(401, "invalid_session");
            case ACCOUNT_UNAVAILABLE -> error(403, "account_unavailable");
            case AUTH_UNAVAILABLE -> error(503, "auth_unavailable");
        };
    }

    @ExceptionHandler(ProjectSnapshotClient.Rejected.class)
    ResponseEntity<?> snapshotFailure(ProjectSnapshotClient.Rejected rejected) {
        return switch (rejected.failure()) {
            case INVALID_SESSION -> error(401, "invalid_session");
            case FORBIDDEN -> error(403, "project_access_unavailable");
            case NOT_FOUND -> error(404, "project_not_found");
            case SNAPSHOT_UNAVAILABLE -> error(409, "snapshot_unavailable");
            case UPSTREAM_UNAVAILABLE, INVALID_RESPONSE -> error(503, "project_unavailable");
        };
    }

    @ExceptionHandler({InterviewSessions.WriteBlocked.class, InterviewSessions.DeletionConflict.class})
    ResponseEntity<?> writeBlocked() { return error(409, "interview_unavailable"); }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> badRequest() { return error(400, "invalid_request"); }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<?> unavailable() { return error(503, "interview_unavailable"); }

    private static ResponseEntity<?> error(int status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code));
    }
}
