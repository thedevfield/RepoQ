package dev.devfield.repoq.interview.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.devfield.repoq.interview.project.ProjectSnapshot;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "devfield.interview.sessions.enabled=true",
        "devfield.interview.first-question.fake-enabled=false",
        "devfield.interview.first-question.ollama-enabled=true",
        "devfield.interview.first-question.poll-ms=100"
})
class OllamaScheduledInterviewIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID EVIDENCE = UUID.randomUUID();
    private static final String SNAPSHOT_ID = UUID.randomUUID().toString();
    private static final String TOKEN = "fixture." + OWNER + ".signature";
    private static final String SUMMARY = "Declares a Java class.";
    private static final AtomicReference<JsonNode> OLLAMA_REQUEST = new AtomicReference<>();
    private static HttpServer upstream;

    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate db;
    @MockitoBean JwtDecoder decoder;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/internal/auth/session-check", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(204, -1);
            }
        });
        upstream.createContext("/internal/projects/" + PROJECT + "/interview-data", exchange -> {
            try (exchange) {
                String member = UUID.randomUUID().toString();
                String repository = UUID.randomUUID().toString();
                String sha = "a".repeat(40);
                var snapshot = new ProjectSnapshot(SNAPSHOT_ID, 1,
                        List.of(new ProjectSnapshot.Member(member, repository, "42", sha)),
                        List.of(new ProjectSnapshot.Topic("JAVA", EVIDENCE.toString())), List.of(),
                        List.of(new ProjectSnapshot.Evidence(EVIDENCE.toString(), member, repository, "42", sha,
                                "src/Main.java", 1, 1, "CODE_FACT", SUMMARY)));
                byte[] body = JSON.writeValueAsBytes(snapshot);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        upstream.createContext("/api/chat", exchange -> {
            try (exchange) {
                OLLAMA_REQUEST.set(JSON.readTree(exchange.getRequestBody()));
                byte[] body = OllamaFirstQuestionGeneratorTest.envelope(
                        OllamaFirstQuestionGeneratorTest.content(EVIDENCE), true, "stop");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        upstream.start();
        int mockPort = upstream.getAddress().getPort();
        String base = "http://127.0.0.1:" + mockPort;
        registry.add("devfield.interview.auth.session-check-uri", () -> base + "/internal/auth/session-check");
        registry.add("devfield.interview.auth.service-key", () -> "auth-check-test-credential");
        registry.add("devfield.interview.project.base-url", () -> base);
        registry.add("devfield.interview.project.service-key", () -> "x01-read-only-test-credential-012345678");
        registry.add("devfield.interview.first-question.ollama-port", () -> mockPort);
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) upstream.stop(0);
    }

    @AfterEach
    void clearSession() {
        db.update("DELETE FROM interview.interview_session WHERE owner_user_id=?", OWNER);
    }

    @Test
    void scheduledOllamaWorkerPublishesFirstQuestionToSessionDetail() throws Exception {
        var generators = context.getBeansOfType(FirstQuestionGenerator.class);
        assertEquals(1, generators.size());
        assertInstanceOf(OllamaFirstQuestionGenerator.class, generators.values().iterator().next());
        when(decoder.decode(TOKEN)).thenReturn(Jwt.withTokenValue(TOKEN).header("alg", "RS256")
                .subject(OWNER.toString()).issuedAt(Instant.now().minusSeconds(1))
                .expiresAt(Instant.now().plusSeconds(120)).build());

        try (var http = HttpClient.newHttpClient()) {
            var start = http.send(request("/api/interviews").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"projectId":"%s","mode":"FREE","topicKey":"JAVA"}
                            """.formatted(PROJECT))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, start.statusCode(), start.body());
            var created = JSON.readTree(start.body());
            assertEquals("PREPARING", created.path("status").asText());
            UUID id = UUID.fromString(created.path("interviewId").asText());

            var detailRequest = request("/api/interviews/" + id).GET().build();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            JsonNode detail;
            do {
                var response = http.send(detailRequest, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
                detail = JSON.readTree(response.body());
                if (!"PREPARING".equals(detail.path("status").asText())) break;
                Thread.sleep(50);
            } while (System.nanoTime() < deadline);

            assertEquals("ACTIVE", detail.path("status").asText(), detail.toString());
            assertEquals(SNAPSHOT_ID, detail.path("snapshotId").asText());
            assertEquals("JAVA", detail.at("/currentQuestion/topicKey").asText());
            assertEquals(JSON.readTree(OllamaFirstQuestionGeneratorTest.content(EVIDENCE)).path("question"),
                    detail.at("/currentQuestion/text"));
        }

        JsonNode sent = OLLAMA_REQUEST.get();
        assertNotNull(sent);
        assertEquals("qwen3.5:4b", sent.path("model").asText());
        assertEquals(EVIDENCE.toString(), sent.at("/format/properties/evidenceId/enum/0").asText());
        assertEquals(SUMMARY, JSON.readTree(sent.at("/messages/1/content").asText()).path("codeFact").asText());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + TOKEN).timeout(Duration.ofSeconds(5));
    }
}
