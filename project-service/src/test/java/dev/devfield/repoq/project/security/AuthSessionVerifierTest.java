package dev.devfield.repoq.project.security;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.devfield.repoq.project.api.ApiProblem;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthSessionVerifierTest {
    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(204);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/auth/session-check", exchange -> {
            assertEquals("Bearer user-token", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("project", exchange.getRequestHeaders().getFirst("X-DevField-Service"));
            assertEquals("test-secret", exchange.getRequestHeaders().getFirst("X-DevField-Service-Key"));
            assertEquals("POST", exchange.getRequestMethod());
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void mapsAuthResultAndFailsClosed() {
        var verifier = new AuthSessionVerifier("http://127.0.0.1:" + server.getAddress().getPort() +
                "/internal/auth/session-check", "test-secret");
        assertDoesNotThrow(() -> verifier.verify("Bearer user-token"));
        status.set(401);
        assertEquals("invalid_session", assertThrows(ApiProblem.class, () -> verifier.verify("Bearer user-token")).code());
        status.set(403);
        assertEquals("account_unavailable", assertThrows(ApiProblem.class, () -> verifier.verify("Bearer user-token")).code());
        status.set(503);
        assertEquals("auth_unavailable", assertThrows(ApiProblem.class, () -> verifier.verify("Bearer user-token")).code());
        server.stop(0);
        assertEquals("auth_unavailable", assertThrows(ApiProblem.class, () -> verifier.verify("Bearer user-token")).code());
    }
}
