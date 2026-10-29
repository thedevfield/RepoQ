package dev.devfield.repoq.project.security;

import dev.devfield.repoq.project.api.ApiProblem;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AuthSessionVerifier {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI uri;
    private final String serviceKey;

    public AuthSessionVerifier(@Value("${devfield.project.auth.session-check-uri}") String uri,
                               @Value("${devfield.project.auth.service-key}") String serviceKey) {
        if (uri.isBlank() || serviceKey.isBlank()) throw new IllegalArgumentException("AUTH-15 configuration is required");
        this.uri = URI.create(uri);
        if (!this.uri.isAbsolute() || !this.uri.getPath().equals("/internal/auth/session-check"))
            throw new IllegalArgumentException("Invalid AUTH-15 URI");
        this.serviceKey = serviceKey;
    }

    public void verify(String bearer) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                    .header("Authorization", bearer)
                    .header("X-DevField-Service", "project")
                    .header("X-DevField-Service-Key", serviceKey)
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 204) return;
            if (status == 401) throw new ApiProblem(HttpStatus.UNAUTHORIZED, "invalid_session");
            if (status == 403) throw new ApiProblem(HttpStatus.FORBIDDEN, "account_unavailable");
            throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "auth_unavailable");
        } catch (ApiProblem e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "auth_unavailable");
        }
    }
}
