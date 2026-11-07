package dev.devfield.repoq.interview.security;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

@Component
public class InterviewAuthentication {
    public enum Failure { INVALID_SESSION, ACCOUNT_UNAVAILABLE, AUTH_UNAVAILABLE }

    public static final class Rejected extends RuntimeException {
        private final Failure failure;

        Rejected(Failure failure) {
            super(failure.name());
            this.failure = failure;
        }

        public Failure failure() { return failure; }
    }

    private final JwtDecoder decoder;
    private final String sessionCheckUri;
    private final String serviceKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public InterviewAuthentication(JwtDecoder decoder,
                                   @Value("${devfield.interview.auth.session-check-uri}") String sessionCheckUri,
                                   @Value("${devfield.interview.auth.service-key}") String serviceKey) {
        this.decoder = decoder;
        this.sessionCheckUri = sessionCheckUri;
        this.serviceKey = serviceKey;
    }

    public void verify(String bearer) {
        if (bearer == null || !bearer.matches("Bearer [A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))
            throw new Rejected(Failure.INVALID_SESSION);
        try {
            decoder.decode(bearer.substring(7));
        } catch (BadJwtException e) {
            throw new Rejected(Failure.INVALID_SESSION);
        } catch (JwtException e) {
            throw new Rejected(Failure.AUTH_UNAVAILABLE);
        }
        try {
            URI uri = URI.create(sessionCheckUri);
            if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) ||
                    !"/internal/auth/session-check".equals(uri.getRawPath()) || uri.getRawUserInfo() != null ||
                    uri.getRawQuery() != null || uri.getRawFragment() != null || serviceKey.isBlank())
                throw new Rejected(Failure.AUTH_UNAVAILABLE);
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                    .header("Authorization", bearer)
                    .header("X-DevField-Service", "interview")
                    .header("X-DevField-Service-Key", serviceKey)
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            // AUTH-15 returns no body on success. Stop at the headers on every response.
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            response.body().close();
            if (status == 204) return;
            if (status == 401) throw new Rejected(Failure.INVALID_SESSION);
            if (status == 403) throw new Rejected(Failure.ACCOUNT_UNAVAILABLE);
            throw new Rejected(Failure.AUTH_UNAVAILABLE);
        } catch (IOException | IllegalArgumentException e) {
            throw new Rejected(Failure.AUTH_UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Rejected(Failure.AUTH_UNAVAILABLE);
        }
    }
}
