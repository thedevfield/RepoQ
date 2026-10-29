package dev.devfield.repoq.project.github;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.devfield.repoq.project.api.ApiProblem;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubApiClientTest {
    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/owner/repo", exchange -> {
            assertEquals("Bearer test-token", exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"id\":1234567,\"private\":false,\"full_name\":\"owner/repo\",\"name\":\"repo\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-RateLimit-Remaining", "0");
            exchange.sendResponseHeaders(status.get(), body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void parsesStableIdAndSeparatesFailureKinds() {
        var client = new GitHubApiClient("test-token", URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
        assertEquals("1234567", client.fetch("owner", "repo").id());
        status.set(404);
        assertEquals("repository_unavailable", assertThrows(ApiProblem.class, () -> client.fetch("owner", "repo")).code());
        status.set(403);
        assertEquals("github_rate_limited", assertThrows(ApiProblem.class, () -> client.fetch("owner", "repo")).code());
        status.set(302);
        assertEquals("github_unavailable", assertThrows(ApiProblem.class, () -> client.fetch("owner", "repo")).code());
    }
}
