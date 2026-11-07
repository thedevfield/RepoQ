package dev.devfield.repoq.interview.project;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import dev.devfield.repoq.interview.security.InterviewAuthentication;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProjectSnapshotClient {
    public enum Failure { INVALID_SESSION, FORBIDDEN, NOT_FOUND, SNAPSHOT_UNAVAILABLE, UPSTREAM_UNAVAILABLE, INVALID_RESPONSE }

    public static final class Rejected extends RuntimeException {
        private final Failure failure;

        private Rejected(Failure failure) {
            super(failure.name());
            this.failure = failure;
        }

        public Failure failure() { return failure; }
    }

    static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();

    private final InterviewAuthentication auth;
    private final String baseUrl;
    private final String key;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @org.springframework.beans.factory.annotation.Autowired
    public ProjectSnapshotClient(InterviewAuthentication auth,
                                 @Value("${devfield.interview.project.base-url}") String baseUrl,
                                 @Value("${devfield.interview.project.service-key}") String key) {
        this(auth, baseUrl, key, Duration.ofSeconds(35));
    }

    ProjectSnapshotClient(InterviewAuthentication auth, String baseUrl, String key, Duration timeout) {
        this.auth = auth;
        this.baseUrl = baseUrl;
        this.key = key;
        this.timeout = timeout;
    }

    public ProjectSnapshot fetch(UUID projectId, String bearer) {
        auth.verify(bearer);
        HttpRequest request;
        try {
            URI base = URI.create(baseUrl);
            if (base.getHost() == null || !("http".equals(base.getScheme()) || "https".equals(base.getScheme())) ||
                    !(base.getRawPath().isEmpty() || base.getRawPath().equals("/")) || base.getRawUserInfo() != null ||
                    base.getRawQuery() != null || base.getRawFragment() != null || projectId == null ||
                    key.length() < 32 || key.length() > 512)
                throw new Rejected(Failure.UPSTREAM_UNAVAILABLE);
            request = HttpRequest.newBuilder(base.resolve("/internal/projects/" + projectId +
                            "/interview-data?evidenceScope=PUBLIC_ONLY")).timeout(timeout)
                    .header("Accept", "application/json")
                    .header("Authorization", bearer)
                    .header("X-DevField-Service", "interview")
                    .header("X-DevField-Service-Key", key).GET().build();
        } catch (IllegalArgumentException e) {
            throw new Rejected(Failure.UPSTREAM_UNAVAILABLE);
        }

        var future = http.sendAsync(request, info -> info.statusCode() == 200
                ? new BoundedBody() : HttpResponse.BodySubscribers.replacing(new byte[0]));
        try {
            var response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) {
                throw new Rejected(switch (response.statusCode()) {
                    case 401 -> Failure.INVALID_SESSION;
                    case 403 -> Failure.FORBIDDEN;
                    case 404 -> Failure.NOT_FOUND;
                    case 409 -> Failure.SNAPSHOT_UNAVAILABLE;
                    default -> Failure.UPSTREAM_UNAVAILABLE;
                });
            }
            if (response.headers().firstValue("Content-Type").stream().noneMatch(value ->
                    value.split(";", 2)[0].strip().equalsIgnoreCase("application/json")))
                throw new Rejected(Failure.INVALID_RESPONSE);
            ProjectSnapshot snapshot = JSON.readValue(response.body(), ProjectSnapshot.class);
            if (snapshot == null) throw new Rejected(Failure.INVALID_RESPONSE);
            return snapshot;
        } catch (IOException e) {
            throw new Rejected(Failure.INVALID_RESPONSE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Rejected(Failure.UPSTREAM_UNAVAILABLE);
        } catch (ExecutionException e) {
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof Rejected rejected) throw rejected;
            }
            throw new Rejected(Failure.UPSTREAM_UNAVAILABLE);
        } catch (TimeoutException e) {
            throw new Rejected(Failure.UPSTREAM_UNAVAILABLE);
        } finally {
            future.cancel(true);
        }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> body = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long size;

        public CompletionStage<byte[]> getBody() { return body.getBody(); }

        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            body.onSubscribe(subscription);
        }

        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) size += buffer.remaining();
            if (size > MAX_BYTES) {
                subscription.cancel();
                body.onError(new Rejected(Failure.INVALID_RESPONSE));
                return;
            }
            body.onNext(buffers);
        }

        public void onError(Throwable error) { body.onError(error); }
        public void onComplete() { body.onComplete(); }
    }
}
