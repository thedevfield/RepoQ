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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "devfield.interview.sessions.enabled=true",
        "devfield.interview.first-question.fake-enabled=false",
        "devfield.interview.first-question.ollama-enabled=true",
        "devfield.interview.first-question.poll-ms=100",
        "devfield.interview.follow-up.enabled=true",
        "devfield.interview.follow-up.poll-ms=3600000",
        "devfield.interview.deletion.project-key-sha256=4acb595e5ada8c9a973db7268d56e9ed27bc097157ce781b7ef0ce5ef401e95d",
        "devfield.interview.deletion.auth-key-sha256=9dc1622a4d732fd2b95d9733ff02dbdf1364f60465c4d10a8badcf779cab4c3e"
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
    private record Reply(int status, String content, CountDownLatch entered, CountDownLatch release) {
        Reply(int status, String content) { this(status, content, null, null); }
    }

    private static final AtomicReference<Reply> FOLLOW_UP_REPLY = new AtomicReference<>();
    private static final AtomicReference<JsonNode> FOLLOW_UP_REQUEST = new AtomicReference<>();
    private static HttpServer upstream;
    private static ExecutorService upstreamThreads;

    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate db;
    @Autowired FollowUpWorker followUp;
    @MockitoBean JwtDecoder decoder;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        if (upstream == null) startUpstream();
        int mockPort = upstream.getAddress().getPort();
        String base = "http://127.0.0.1:" + mockPort;
        registry.add("devfield.interview.auth.session-check-uri", () -> base + "/internal/auth/session-check");
        registry.add("devfield.interview.auth.service-key", () -> "auth-check-test-credential");
        registry.add("devfield.interview.project.base-url", () -> base);
        registry.add("devfield.interview.project.service-key", () -> "x01-read-only-test-credential-012345678");
        registry.add("devfield.interview.first-question.ollama-port", () -> mockPort);
    }

    private static void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstreamThreads = Executors.newVirtualThreadPerTaskExecutor();
        upstream.setExecutor(upstreamThreads);
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
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                String content = OllamaFirstQuestionGeneratorTest.content(EVIDENCE);
                if (request.at("/format/properties/action").isObject()) {
                    FOLLOW_UP_REQUEST.set(request);
                    Reply reply = FOLLOW_UP_REPLY.get();
                    if (reply.entered() != null) {
                        reply.entered().countDown();
                        try {
                            if (!reply.release().await(10, TimeUnit.SECONDS)) throw new IOException("Test reply not released");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException(interrupted);
                        }
                    }
                    if (reply.status() != 200) {
                        exchange.sendResponseHeaders(reply.status(), -1);
                        return;
                    }
                    content = reply.content();
                } else {
                    OLLAMA_REQUEST.set(request);
                }
                byte[] body = OllamaFirstQuestionGeneratorTest.envelope(content, true, "stop");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) upstream.stop(0);
        if (upstreamThreads != null) upstreamThreads.close();
    }

    @AfterEach
    void clearSession() {
        db.update("DELETE FROM interview.interview_session WHERE owner_user_id=?", OWNER);
        db.update("DELETE FROM interview.interview_write_fence WHERE owner_user_id=?", OWNER);
        FOLLOW_UP_REPLY.set(null);
        FOLLOW_UP_REQUEST.set(null);
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


    @Test
    void concurrentReplayAndThreeFollowUpsPreserveOrderedAnswers() throws Exception {
        try (var http = HttpClient.newHttpClient(); var pool = Executors.newFixedThreadPool(2)) {
            UUID id = start(http);
            String first = detail(http, id).at("/currentQuestion/id").asText();
            var a = pool.submit(() -> submit(http, id, first, "  original answer  ", "first"));
            var b = pool.submit(() -> submit(http, id, first, "  original answer  ", "first"));
            assertEquals(202, a.get(10, TimeUnit.SECONDS));
            assertEquals(202, b.get(10, TimeUnit.SECONDS));
            assertEquals(409, submit(http, id, first, "changed", "first"));
            assertEquals(409, submit(http, id, first, "  original answer  ", "other-key"));
            assertEquals("FOLLOW_UP_PENDING", detail(http, id).path("answerProgress").asText());
            assertFalse(detail(http, id).has("currentQuestion"));

            for (int position = 1; position <= 3; position++) {
                String question = "What about trade-off " + position + "?";
                FOLLOW_UP_REPLY.set(new Reply(200, outcome("FOLLOW_UP", question, EVIDENCE)));
                followUp.poll();
                JsonNode current = detail(http, id);
                assertEquals("AWAITING_ANSWER", current.path("answerProgress").asText());
                assertEquals(question, current.at("/currentQuestion/text").asText());
                assertEquals(position, current.path("answers").size());
                var sent = FOLLOW_UP_REQUEST.get();
                assertEquals(EVIDENCE.toString(), sent.at("/format/properties/evidenceId/enum/0").asText());
                var input = JSON.readTree(sent.at("/messages/1/content").asText());
                assertEquals(position, input.path("turns").size());
                assertEquals("  original answer  ", input.at("/turns/0/answer").asText());
                assertEquals(SUMMARY, input.path("codeFact").asText());
                assertEquals(202, submit(http, id, current.at("/currentQuestion/id").asText(),
                        "answer " + position, "follow-" + position));
            }
            FOLLOW_UP_REQUEST.set(null);
            followUp.poll();
            assertNull(FOLLOW_UP_REQUEST.get());
            assertEquals(202, submit(http, id, first, "  original answer  ", "first"));
            JsonNode complete = detail(http, id);
            assertEquals("QUESTIONS_COMPLETE", complete.path("answerProgress").asText());
            assertEquals(0, complete.path("completedBundles").asInt());
            assertEquals(4, complete.path("answers").size());
            assertEquals("answer 3", complete.at("/answers/3/answer").asText());
            assertFalse(complete.has("currentQuestion"));
        }
    }

    @Test
    void answerRejectsOtherOwnerAndBlankText() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            UUID id = start(http);
            String question = detail(http, id).at("/currentQuestion/id").asText();
            assertEquals(400, submit(http, id, question, "  ", "blank"));
            String other = "fixture." + UUID.randomUUID() + ".signature";
            when(decoder.decode(other)).thenReturn(Jwt.withTokenValue(other).header("alg", "RS256")
                    .subject(other.split("\\.")[1]).issuedAt(Instant.now().minusSeconds(1))
                    .expiresAt(Instant.now().plusSeconds(120)).build());
            var request = request("/api/interviews/" + id + "/answers").setHeader("Authorization", "Bearer " + other)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of(
                            "questionId", question, "answer", "other owner's answer", "submissionId", "foreign"))));
            assertEquals(404, http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertTrue(detail(http, id).path("answers").isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void stopOrUnsupportedEvidenceNeverPublishesAQuestion(boolean stop) throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            UUID id = start(http);
            String question = detail(http, id).at("/currentQuestion/id").asText();
            assertEquals(202, submit(http, id, question, "saved", "first"));
            FOLLOW_UP_REPLY.set(new Reply(200, stop ? outcome("STOP", "", EVIDENCE)
                    : outcome("FOLLOW_UP", "Unsupported question?", UUID.randomUUID())));
            followUp.poll();
            JsonNode result = detail(http, id);
            assertEquals(stop ? "QUESTIONS_COMPLETE" : "FOLLOW_UP_FAILED", result.path("answerProgress").asText());
            assertEquals("saved", result.at("/answers/0/answer").asText());
            assertFalse(result.has("currentQuestion"));
        }
    }

    @Test
    void repeatedOllamaFailurePreservesAnswerAfterRetryExhaustion() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            UUID id = start(http);
            String question = detail(http, id).at("/currentQuestion/id").asText();
            assertEquals(202, submit(http, id, question, "kept through failure", "retry"));
            FOLLOW_UP_REPLY.set(new Reply(503, null));
            for (int attempt = 1; attempt <= 3; attempt++) {
                followUp.poll();
                assertEquals(attempt, db.queryForObject("SELECT attempt FROM interview.user_answer WHERE session_id=?",
                        Integer.class, id));
                var result = detail(http, id);
                assertEquals(attempt == 3 ? "FOLLOW_UP_FAILED" : "FOLLOW_UP_PENDING", result.path("answerProgress").asText());
                assertEquals("kept through failure", result.at("/answers/0/answer").asText());
                db.update("UPDATE interview.user_answer SET due_at=clock_timestamp() WHERE session_id=?", id);
            }
            assertEquals(202, submit(http, id, question, "kept through failure", "retry"));
            followUp.poll();
            assertEquals("FOLLOW_UP_FAILED", detail(http, id).path("answerProgress").asText());
        }
    }

    @Test
    void expiredAttemptCannotPublishWhileRetryIsRunning() throws Exception {
        try (var http = HttpClient.newHttpClient(); var pool = Executors.newFixedThreadPool(2)) {
            UUID id = start(http);
            String question = detail(http, id).at("/currentQuestion/id").asText();
            assertEquals(202, submit(http, id, question, "answer", "first"));
            var firstEntered = new CountDownLatch(1);
            var firstRelease = new CountDownLatch(1);
            var retryEntered = new CountDownLatch(1);
            var retryRelease = new CountDownLatch(1);
            FOLLOW_UP_REPLY.set(new Reply(200, outcome("FOLLOW_UP", "Expired question?", EVIDENCE),
                    firstEntered, firstRelease));
            var first = pool.submit(followUp::poll);
            try {
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
                db.update("""
                        UPDATE interview.user_answer SET due_at=clock_timestamp(),lease_until=clock_timestamp()
                        WHERE session_id=?
                        """, id);
                FOLLOW_UP_REPLY.set(new Reply(200, outcome("FOLLOW_UP", "Valid question?", EVIDENCE),
                        retryEntered, retryRelease));
                var retry = pool.submit(followUp::poll);
                assertTrue(retryEntered.await(5, TimeUnit.SECONDS));
                firstRelease.countDown();
                first.get(10, TimeUnit.SECONDS);
                JsonNode pending = detail(http, id);
                assertEquals("FOLLOW_UP_PENDING", pending.path("answerProgress").asText());
                assertFalse(pending.has("currentQuestion"));
                retryRelease.countDown();
                retry.get(10, TimeUnit.SECONDS);
                assertEquals("Valid question?", detail(http, id).at("/currentQuestion/text").asText());
            } finally {
                firstRelease.countDown();
                retryRelease.countDown();
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void committedDeletionBarrierBlocksSubmissionAndLateOllamaResult(boolean userDeletion) throws Exception {
        try (var http = HttpClient.newHttpClient(); var pool = Executors.newSingleThreadExecutor()) {
            UUID id = start(http);
            String question = detail(http, id).at("/currentQuestion/id").asText();
            assertEquals(202, submit(http, id, question, "delete me", "before"));
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            FOLLOW_UP_REPLY.set(new Reply(200, outcome("FOLLOW_UP", "Late question?", EVIDENCE), entered, release));
            String blocker = "interview.test_follow_up_" + id.toString().replace("-", "");
            UUID job = UUID.randomUUID();
            db.execute("CREATE TABLE " + blocker + " (id uuid REFERENCES interview.interview_session(id))");
            try {
                db.update("INSERT INTO " + blocker + " VALUES (?)", id);
                var running = pool.submit(followUp::poll);
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    assertEquals(503, delete(http, userDeletion, job));
                    assertEquals(409, submit(http, id, question, "late submit", "after"));
                    assertEquals(409, submit(http, id, question, "delete me", "before"));
                    assertEquals(404, http.send(request("/api/interviews/" + id).GET().build(),
                            HttpResponse.BodyHandlers.discarding()).statusCode());
                } finally {
                    release.countDown();
                }
                running.get(10, TimeUnit.SECONDS);
                assertEquals(1, db.queryForObject("SELECT count(*) FROM interview.question_turn WHERE session_id=?",
                        Integer.class, id));
                assertEquals("RUNNING", db.queryForObject("SELECT state FROM interview.user_answer WHERE session_id=?",
                        String.class, id));
            } finally {
                db.execute("DROP TABLE " + blocker);
            }
            assertEquals(204, delete(http, userDeletion, job));
            assertEquals(204, delete(http, userDeletion, job));
            assertEquals(0, db.queryForObject("SELECT count(*) FROM interview.user_answer WHERE session_id=?",
                    Integer.class, id));
            assertEquals(0, db.queryForObject("SELECT count(*) FROM interview.question_turn WHERE session_id=?",
                    Integer.class, id));
        }
    }

    @Nested
    @TestPropertySource(properties = "devfield.interview.follow-up.poll-ms=100")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class ScheduledFollowUp {
        @LocalServerPort int scheduledPort;
        @MockitoBean JwtDecoder scheduledDecoder;

        @Test
        void publishesFollowUpAfterAnswerSubmission() throws Exception {
            port = scheduledPort;
            String followUpText = "What trade-off did you consider?";
            FOLLOW_UP_REPLY.set(new Reply(200, outcome("FOLLOW_UP", followUpText, EVIDENCE)));
            try (var http = HttpClient.newHttpClient()) {
                UUID id = start(http, scheduledDecoder);
                String question = detail(http, id).at("/currentQuestion/id").asText();
                assertEquals(202, submit(http, id, question, "I chose simplicity.", "scheduled"));

                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                JsonNode result = detail(http, id);
                while ("FOLLOW_UP_PENDING".equals(result.path("answerProgress").asText())
                        && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                    result = detail(http, id);
                }
                assertEquals("AWAITING_ANSWER", result.path("answerProgress").asText(), result.toString());
                assertEquals(followUpText, result.at("/currentQuestion/text").asText());
                assertEquals(question, result.at("/answers/0/questionId").asText());
                assertEquals("I chose simplicity.", result.at("/answers/0/answer").asText());

                JsonNode sent = FOLLOW_UP_REQUEST.get();
                assertNotNull(sent);
                assertEquals(EVIDENCE.toString(), sent.at("/format/properties/evidenceId/enum/0").asText());
                var input = JSON.readTree(sent.at("/messages/1/content").asText());
                assertEquals("I chose simplicity.", input.at("/turns/0/answer").asText());
            }
        }
    }

    private static String outcome(String action, String question, UUID evidence) {
        return JSON.valueToTree(Map.of("action", action, "question", question, "evidenceId", evidence)).toString();
    }

    private UUID start(HttpClient http) throws Exception {
        return start(http, decoder);
    }

    private UUID start(HttpClient http, JwtDecoder jwtDecoder) throws Exception {
        when(jwtDecoder.decode(TOKEN)).thenReturn(Jwt.withTokenValue(TOKEN).header("alg", "RS256")
                .subject(OWNER.toString()).issuedAt(Instant.now().minusSeconds(1))
                .expiresAt(Instant.now().plusSeconds(120)).build());
        var response = http.send(request("/api/interviews").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
                        Map.of("projectId", PROJECT, "mode", "FREE", "topicKey", "JAVA")))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), response.body());
        UUID id = UUID.fromString(JSON.readTree(response.body()).path("interviewId").asText());
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        do {
            if ("ACTIVE".equals(detail(http, id).path("status").asText())) return id;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("First question did not become ACTIVE");
    }

    private JsonNode detail(HttpClient http, UUID id) throws Exception {
        var response = http.send(request("/api/interviews/" + id).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private int submit(HttpClient http, UUID id, String question, String answer, String submission) throws Exception {
        return http.send(request("/api/interviews/" + id + "/answers").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of(
                        "questionId", question, "answer", answer, "submissionId", submission)))).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private int delete(HttpClient http, boolean userDeletion, UUID job) throws Exception {
        String path = userDeletion ? "/internal/users/" + OWNER + "/data" : "/internal/projects/" + PROJECT + "/interviews";
        var command = userDeletion ? Map.of("userId", OWNER, "deletionJobId", job, "state", "DELETING")
                : Map.of("ownerUserId", OWNER, "projectId", PROJECT, "deletionJobId", job);
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("X-DevField-Service", userDeletion ? "auth" : "project")
                .header("X-DevField-Service-Key", userDeletion ? "x04-auth-test-credential-only-012345678"
                        : "x02-project-test-credential-only-012345")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(command))).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + TOKEN).timeout(Duration.ofSeconds(5));
    }
}
