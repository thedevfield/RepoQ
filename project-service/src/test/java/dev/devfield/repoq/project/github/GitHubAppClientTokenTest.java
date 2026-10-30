package dev.devfield.repoq.project.github;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.devfield.repoq.project.api.ApiProblem;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubAppClientTokenTest {
    private HttpServer server;
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> accept = new AtomicReference<>();
    private final AtomicReference<String> payload = new AtomicReference<>();
    private GitHubAppClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login/oauth/access_token", exchange -> {
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        client = new GitHubAppClient("test-client", "test-secret", 2,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/login/oauth/access_token"));
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void exchangesCodeAndRefreshAsJsonWhileKeepingRestAccept() {
        response.set("{\"access_token\":\"test-access\",\"refresh_token\":\"test-refresh\",\"expires_in\":28800," +
                "\"refresh_token_expires_in\":15897600,\"token_type\":\"bearer\"}");
        Instant before = Instant.now();
        var result = client.exchange("test code", "test-verifier", "http://localhost/callback");
        assertEquals("application/json", accept.get());
        assertEquals("application/vnd.github+json", client.accept(URI.create("https://api.github.com/user")));
        assertTrue(payload.get().contains("code=test+code"));
        assertTrue(payload.get().contains("code_verifier=test-verifier"));
        assertTrue(payload.get().contains("redirect_uri=http%3A%2F%2Flocalhost%2Fcallback"));
        assertEquals("test-access", result.access());
        assertEquals("test-refresh", result.refresh());
        assertTrue(result.accessExpiry().isAfter(before.plusSeconds(28799)));
        assertTrue(result.refreshExpiry().isAfter(before.plusSeconds(15897599)));

        var refreshed = client.refresh("test-refresh");
        assertEquals("test-access", refreshed.access());
        assertTrue(payload.get().contains("grant_type=refresh_token&refresh_token=test-refresh"));
        assertEquals("application/json", accept.get());
    }

    @Test
    void rejectsOAuthErrorAndMalformedTokenFields() {
        for (String invalid : new String[] {
                "{\"error\":\"bad_verification_code\"}",
                "{\"access_token\":\"test-access\",\"refresh_token\":\"test-refresh\",\"expires_in\":\"28800\",\"refresh_token_expires_in\":15897600,\"token_type\":\"bearer\"}",
                "{\"access_token\":\"test-access\",\"refresh_token\":\"test-refresh\",\"expires_in\":28800,\"refresh_token_expires_in\":0,\"token_type\":\"bearer\"}",
                "{\"access_token\":\"test-access\",\"refresh_token\":\"test-refresh\",\"expires_in\":28800,\"refresh_token_expires_in\":15897600,\"token_type\":\"other\"}"
        }) {
            response.set(invalid);
            assertEquals("github_reauthorization_required", assertThrows(ApiProblem.class,
                    () -> client.exchange("test-code", "test-verifier", "http://localhost/callback")).code());
        }
    }

    @Test
    void rejectsHttpErrorsAndFormEncodedResponse() {
        response.set("{\"error\":\"bad_verification_code\"}");
        status.set(401);
        assertEquals("github_reauthorization_required", assertThrows(ApiProblem.class,
                () -> client.exchange("test-code", "test-verifier", "http://localhost/callback")).code());
        status.set(502);
        assertEquals("github_unavailable", assertThrows(ApiProblem.class,
                () -> client.exchange("test-code", "test-verifier", "http://localhost/callback")).code());
        status.set(200);
        response.set("access_token=test-access&refresh_token=test-refresh");
        assertEquals("github_unavailable", assertThrows(ApiProblem.class,
                () -> client.exchange("test-code", "test-verifier", "http://localhost/callback")).code());
    }
}
