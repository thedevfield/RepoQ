package dev.devfield.repoq.project.deletion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InterviewDeletionClient {
    public record Command(UUID projectId, UUID ownerUserId, UUID deletionJobId) {}
    enum Result {
        COMPLETE, X02_AUTH_REJECTED, X02_DELETION_CONFLICT, X02_REQUEST_REJECTED,
        X02_UNAVAILABLE, X02_CONFIGURATION_ERROR
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json = new ObjectMapper();
    private final URI base;
    private final String key;
    private final Duration timeout;

    public InterviewDeletionClient(@Value("${devfield.project.deletion.interview-url}") String url,
                            @Value("${devfield.project.deletion.service-key}") String key,
                            @Value("${devfield.project.deletion.timeout-seconds}") int timeoutSeconds) {
        this.base = origin(url);
        this.key = key;
        if (timeoutSeconds < 1 || timeoutSeconds > 60) throw new IllegalArgumentException("Invalid X-02 timeout");
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    long timeoutSeconds() { return timeout.toSeconds(); }

    Result delete(Command command) {
        if (base == null || key.length() < 32 || key.length() > 512) return Result.X02_CONFIGURATION_ERROR;
        try {
            var request = HttpRequest.newBuilder(base.resolve("/internal/projects/" + command.projectId() + "/interviews"))
                    .timeout(timeout).header("Content-Type", "application/json")
                    .header("X-DevField-Service", "project").header("X-DevField-Service-Key", key)
                    .method("DELETE", HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(command))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            response.body().close();
            return switch (response.statusCode()) {
                case 204 -> Result.COMPLETE;
                case 401, 403 -> Result.X02_AUTH_REJECTED;
                case 409 -> Result.X02_DELETION_CONFLICT;
                case 400, 404, 405 -> Result.X02_REQUEST_REJECTED;
                default -> Result.X02_UNAVAILABLE;
            };
        } catch (IllegalArgumentException | JsonProcessingException e) {
            return Result.X02_CONFIGURATION_ERROR;
        } catch (IOException e) {
            return Result.X02_UNAVAILABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.X02_UNAVAILABLE;
        }
    }

    private static URI origin(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null ||
                    !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) return null;
            boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
            return "https".equalsIgnoreCase(uri.getScheme()) || loopback && "http".equalsIgnoreCase(uri.getScheme())
                    ? uri : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
