package dev.devfield.repoq.interview.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.devfield.repoq.interview.project.ProjectSnapshotClient;
import dev.devfield.repoq.interview.security.InterviewAuthentication;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
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
    public record HistoryPage(List<InterviewSessions.HistoryItem> items,
                              @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {}

    private record HistoryCursor(OffsetDateTime createdAt, UUID id) {
        static HistoryCursor decode(UUID project, String token) {
            if (token.length() > 256) throw new IllegalArgumentException();
            String[] fields = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 4 || !"v1".equals(fields[0]) || !project.toString().equals(fields[1])) {
                throw new IllegalArgumentException();
            }
            var timestamp = OffsetDateTime.parse(fields[2]).withOffsetSameInstant(ZoneOffset.UTC);
            if (timestamp.getYear() < 1 || timestamp.getYear() > 9999 || timestamp.getNano() % 1000 != 0) {
                throw new IllegalArgumentException();
            }
            UUID id = UUID.fromString(fields[3]);
            if (!id.toString().equals(fields[3])) throw new IllegalArgumentException();
            return new HistoryCursor(timestamp, id);
        }

        static String encode(UUID project, InterviewSessions.HistoryItem item) {
            String value = "v1|" + project + "|" + item.createdAt() + "|" + item.interviewId();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    public record Start(UUID projectId, InterviewSessions.Mode mode, String topicKey) {}
    public record Answer(UUID questionId, String answer, String submissionId) {}
    public record LifecycleChange(String action) {}
    public record NextBundle(UUID previousBundleId, String topicKey) {}
    public record Detail(UUID interviewId, InterviewSessions.Mode mode, String status, String snapshotId,
                         String topicKey, int completedBundles,
                         @JsonInclude(JsonInclude.Include.NON_NULL) InterviewSessions.CurrentQuestion currentQuestion,
                         @JsonInclude(JsonInclude.Include.NON_NULL) List<InterviewSessions.SavedAnswer> answers,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String answerProgress,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String evaluationStatus,
                         @JsonInclude(JsonInclude.Include.NON_NULL) InterviewSessions.Evaluation evaluation,
                         @JsonInclude(JsonInclude.Include.NON_NULL) UUID currentBundleId,
                         @JsonInclude(JsonInclude.Include.NON_NULL) List<InterviewSessions.CompletedBundle> completedBundleHistory,
                         @JsonInclude(JsonInclude.Include.NON_NULL) InterviewSessions.MockSummary sessionEvaluation) {
        static Detail from(InterviewSessions.Session session, boolean followUpEnabled, boolean showBundles) {
            boolean showAnswers = followUpEnabled || !session.answers().isEmpty();
            showBundles = showBundles || !session.bundleId().equals(session.id()) || session.managedMock();
            return new Detail(session.id(), session.mode(), session.state(), session.snapshot().snapshotId(),
                    session.topicKey(), session.completedBundles(),
                    session.currentQuestion(), showAnswers ? session.answers() : null,
                    showAnswers ? session.answerProgress() : null, session.evaluationStatus(), session.evaluation(),
                    showBundles ? session.bundleId() : null,
                    showBundles && (!session.managedMock() || session.mockSummary() != null)
                            ? session.completedBundleHistory() : null, session.mockSummary());
        }
    }

    private final InterviewAuthentication auth;
    private final ProjectSnapshotClient snapshots;
    private final InterviewSessions sessions;
    private final InterviewAnswers answers;
    private final boolean followUpEnabled;
    private final InterviewBundles bundles;
    private final boolean nextBundleEnabled;
    private final InterviewLifecycle lifecycle;
    private final boolean lifecycleEnabled;
    private final boolean sessionDeletionEnabled;
    private final boolean historyEnabled;
    private final boolean mockEnabled;
    private final boolean evaluationEnabled;

    public InterviewSessionController(InterviewAuthentication auth, ProjectSnapshotClient snapshots,
                                      InterviewSessions sessions, InterviewAnswers answers, InterviewBundles bundles, InterviewLifecycle lifecycle,
                                      @Value("${devfield.interview.follow-up.enabled:false}") boolean followUpEnabled,
                                      @Value("${devfield.interview.next-bundle.enabled:false}") boolean nextBundleEnabled,
                                      @Value("${devfield.interview.lifecycle.enabled:false}") boolean lifecycleEnabled,
                                      @Value("${devfield.interview.session-deletion.enabled:false}") boolean sessionDeletionEnabled,
                                      @Value("${devfield.interview.history.enabled:false}") boolean historyEnabled,
                                      @Value("${devfield.interview.mock.enabled:false}") boolean mockEnabled,
                                      @Value("${devfield.interview.evaluation.enabled:false}") boolean evaluationEnabled) {
        this.auth = auth;
        this.snapshots = snapshots;
        this.sessions = sessions;
        this.answers = answers;
        this.followUpEnabled = followUpEnabled;
        this.bundles = bundles;
        this.nextBundleEnabled = nextBundleEnabled;
        this.lifecycle = lifecycle;
        this.lifecycleEnabled = lifecycleEnabled;
        this.sessionDeletionEnabled = sessionDeletionEnabled;
        this.historyEnabled = historyEnabled;
        this.mockEnabled = mockEnabled;
        this.evaluationEnabled = evaluationEnabled;
    }

    @PostMapping("/api/interviews")
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt, @RequestBody Start request) {
        if (request.projectId() == null || request.mode() == null) return error(400, "invalid_request");
        boolean mock = request.mode() == InterviewSessions.Mode.MOCK;
        if (mock && !mockEnabled) return error(400, "interview_mode_not_supported");
        if (mock && (!followUpEnabled || !lifecycleEnabled || !sessionDeletionEnabled || !evaluationEnabled)) {
            return error(503, "interview_unavailable");
        }
        String topic = request.topicKey();
        if (mock ? topic != null : topic == null || !topic.matches("[A-Z][A-Z0-9_]{0,63}")) {
            return error(400, "invalid_topic");
        }

        // X-01 verifies AUTH-15 and runs before taking any Interview database locks.
        var snapshot = snapshots.fetch(request.projectId(), "Bearer " + jwt.getTokenValue());
        if (!mock && !"RANDOM".equals(topic) && snapshot.topics().stream().noneMatch(candidate -> topic.equals(candidate.key())))
            return error(400, "invalid_topic");

        var session = sessions.create(UUID.fromString(jwt.getSubject()), request.projectId(), request.mode(), snapshot, topic);
        return ResponseEntity.created(URI.create("/api/interviews/" + session.id())).cacheControl(CacheControl.noStore())
                .body(Detail.from(session, followUpEnabled, nextBundleEnabled));
    }

    @GetMapping("/api/interviews")
    public ResponseEntity<?> history(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID projectId,
                                     @RequestParam(defaultValue = "20") int limit,
                                     @RequestParam(required = false) String cursor) {
        if (!historyEnabled) return error(403, "interview_unavailable");
        auth.verify("Bearer " + jwt.getTokenValue());
        if (projectId == null) return error(400, "invalid_request");
        if (limit < 1 || limit > 50) return error(400, "invalid_limit");
        HistoryCursor boundary;
        try {
            boundary = cursor == null ? null : HistoryCursor.decode(projectId, cursor);
        } catch (IllegalArgumentException | DateTimeException invalid) {
            return error(400, "invalid_cursor");
        }
        var rows = sessions.history(UUID.fromString(jwt.getSubject()), projectId,
                boundary == null ? null : boundary.createdAt(), boundary == null ? null : boundary.id(), limit + 1,
                mockEnabled);
        var items = rows.subList(0, Math.min(limit, rows.size()));
        String next = rows.size() > limit ? HistoryCursor.encode(projectId, items.getLast()) : null;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new HistoryPage(items, next));
    }

    @GetMapping("/api/interviews/{id}")
    public ResponseEntity<?> find(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        auth.verify("Bearer " + jwt.getTokenValue());
        var session = sessions.find(UUID.fromString(jwt.getSubject()), id, nextBundleEnabled)
                .filter(found -> found.mode() == InterviewSessions.Mode.FREE || mockEnabled && found.managedMock());
        if (session.isEmpty()) return error(404, "interview_not_found");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Detail.from(session.get(), followUpEnabled, nextBundleEnabled));
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
                body.submissionId(), mockEnabled)) {
            case ACCEPTED -> ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                    .body(Map.of("status", "ACCEPTED"));
            case CONFLICT -> error(409, "answer_conflict");
            case NOT_FOUND -> error(404, "interview_not_found");
        };
    }

    @PostMapping("/api/interviews/{id}/bundles")
    public ResponseEntity<?> nextBundle(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody NextBundle body) {
        if (!nextBundleEnabled) return error(403, "interview_unavailable");
        auth.verify("Bearer " + jwt.getTokenValue());
        if (body.previousBundleId() == null || body.topicKey() == null || !body.topicKey().matches("[A-Z][A-Z0-9_]{0,63}")) {
            return error(400, "invalid_request");
        }
        var result = bundles.request(UUID.fromString(jwt.getSubject()), id, body.previousBundleId(), body.topicKey());
        return switch (result.status()) {
            case ACCEPTED -> ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                    .body(Map.of("bundleId", result.bundleId(), "status", "ACCEPTED"));
            case CONFLICT -> error(409, "bundle_conflict");
            case NOT_FOUND -> error(404, "interview_not_found");
            case INVALID_TOPIC -> error(400, "invalid_topic");
            case UNAVAILABLE -> error(503, "interview_unavailable");
        };
    }

    @PatchMapping("/api/interviews/{id}")
    public ResponseEntity<?> changeLifecycle(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                              @RequestBody LifecycleChange body) {
        if (!lifecycleEnabled) return error(403, "interview_unavailable");
        auth.verify("Bearer " + jwt.getTokenValue());
        if (body.action() == null) return error(400, "invalid_request");
        InterviewLifecycle.Action action;
        try {
            action = InterviewLifecycle.Action.valueOf(body.action());
        } catch (IllegalArgumentException invalid) {
            return error(400, "invalid_request");
        }
        var result = lifecycle.change(UUID.fromString(jwt.getSubject()), id, action, mockEnabled);
        return switch (result.outcome()) {
            case OK -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("status", result.status()));
            case CONFLICT -> error(409, "interview_state_conflict");
            case NOT_FOUND -> error(404, "interview_not_found");
        };
    }

    @DeleteMapping("/api/interviews/{id}")
    public ResponseEntity<?> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                    @RequestParam(defaultValue = "false") boolean confirmAbandon) {
        if (!sessionDeletionEnabled) return error(403, "interview_unavailable");
        auth.verify("Bearer " + jwt.getTokenValue());
        if (!lifecycle.delete(UUID.fromString(jwt.getSubject()), id, mockEnabled, confirmAbandon)) return error(404, "interview_not_found");
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
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
