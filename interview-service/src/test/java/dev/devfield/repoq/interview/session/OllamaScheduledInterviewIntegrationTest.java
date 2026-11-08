package dev.devfield.repoq.interview.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.devfield.repoq.interview.InterviewApplication;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
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
    private static final AtomicInteger SNAPSHOT_CALLS = new AtomicInteger();
    private static final AtomicReference<JsonNode> OLLAMA_REQUEST = new AtomicReference<>();
    private record Reply(int status, String content, CountDownLatch entered, CountDownLatch release) {
        Reply(int status, String content) { this(status, content, null, null); }
    }

    private static final AtomicReference<Reply> FOLLOW_UP_REPLY = new AtomicReference<>();
    private static final AtomicReference<JsonNode> FOLLOW_UP_REQUEST = new AtomicReference<>();
    private static final AtomicReference<Reply> MAIN_REPLY = new AtomicReference<>();
    private static final AtomicReference<Reply> EVALUATION_REPLY = new AtomicReference<>();
    private static final AtomicReference<JsonNode> EVALUATION_REQUEST = new AtomicReference<>();
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
                SNAPSHOT_CALLS.incrementAndGet();
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
                Reply reply = null;
                if (request.at("/format/properties/exampleAnswer").isObject()) {
                    EVALUATION_REQUEST.set(request);
                    reply = EVALUATION_REPLY.get();
                } else if (request.at("/format/properties/action").isObject()) {
                    FOLLOW_UP_REQUEST.set(request);
                    reply = FOLLOW_UP_REPLY.get();
                } else {
                    OLLAMA_REQUEST.set(request);
                    reply = MAIN_REPLY.get();
                }
                if (reply != null) {
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
        SNAPSHOT_CALLS.set(0);
        MAIN_REPLY.set(null);
        EVALUATION_REPLY.set(null);
        EVALUATION_REQUEST.set(null);
    }

    @Test
    void sessionDeletionIsDisabledByDefault() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            UUID id = start(http);
            assertEquals(403, deleteSession(http, id).statusCode());
        }
    }

    @Test
    void lifecycleIsDisabledByDefault() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            UUID id = start(http);
            assertEquals(403, lifecycle(http, id, "PAUSE").statusCode());
        }
    }

    @Test
    void nextBundleIsDisabledByDefault() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            UUID id = start(http);
            assertEquals(403, next(http, id, id.toString(), "JAVA").statusCode());
        }
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
            assertFalse(result.has("evaluationStatus"));
            assertEquals(0, evaluationCount(id));
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
    @TestPropertySource(properties = {
            "devfield.interview.follow-up.poll-ms=100",
            "devfield.interview.evaluation.enabled=true",
            "devfield.interview.evaluation.poll-ms=100",
            "devfield.interview.next-bundle.enabled=true"
    })
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class ScheduledFollowUp {
        @LocalServerPort int scheduledPort;
        @MockitoBean JwtDecoder scheduledDecoder;

        @Test
        void nextBundleReusesPinnedSnapshotAndPublishesSecondEvaluation() throws Exception {
            port = scheduledPort;
            FOLLOW_UP_REPLY.set(new Reply(200, outcome("STOP", "", EVIDENCE)));
            EVALUATION_REPLY.set(new Reply(200, evaluation(EVIDENCE, "First evaluation.")));
            try (var http = HttpClient.newHttpClient(); var executor = Executors.newFixedThreadPool(2)) {
                UUID id = start(http, scheduledDecoder);
                var first = detail(http, id);
                String previous = first.path("currentBundleId").asText();
                String firstQuestion = first.at("/currentQuestion/id").asText();
                String firstQuestionText = first.at("/currentQuestion/text").asText();
                assertEquals(409, next(http, id, previous, "RANDOM").statusCode());
                assertEquals(202, submit(http, id, firstQuestion, "first answer", "bundle-one"));
                assertEquals(1, awaitEvaluation(http, id, "READY").path("completedBundles").asInt());
                assertEquals(400, next(http, id, previous, "UNKNOWN").statusCode());

                MAIN_REPLY.set(new Reply(200, JSON.valueToTree(Map.of(
                        "question", "How would you verify this choice?", "evidenceId", EVIDENCE)).toString()));
                var left = executor.submit(() -> next(http, id, previous, "RANDOM"));
                var right = executor.submit(() -> next(http, id, previous, "RANDOM"));
                var accepted = left.get(10, TimeUnit.SECONDS);
                var replay = right.get(10, TimeUnit.SECONDS);
                assertEquals(202, accepted.statusCode(), accepted.body());
                assertEquals(202, replay.statusCode(), replay.body());
                String successor = JSON.readTree(accepted.body()).path("bundleId").asText();
                assertNotEquals(previous, successor);
                assertEquals(successor, JSON.readTree(replay.body()).path("bundleId").asText());
                assertEquals(409, next(http, id, previous, "JAVA").statusCode());

                var second = awaitActive(http, id);
                assertEquals(successor, second.path("currentBundleId").asText());
                assertEquals(SNAPSHOT_ID, second.path("snapshotId").asText());
                assertEquals("JAVA", second.at("/currentQuestion/topicKey").asText());
                assertEquals("How would you verify this choice?", second.at("/currentQuestion/text").asText());
                assertEquals("First evaluation.", second.at("/completedBundleHistory/0/evaluation/strengths").asText());
                assertEquals(1, second.path("completedBundles").asInt());
                assertTrue(second.path("answers").isEmpty());
                var mainInput = JSON.readTree(OLLAMA_REQUEST.get().at("/messages/1/content").asText());
                assertEquals(EVIDENCE.toString(), mainInput.path("evidenceId").asText());
                assertEquals(firstQuestionText, mainInput.at("/previousQuestions/0").asText());

                assertEquals(202, submit(http, id, firstQuestion, "first answer", "bundle-one"));
                assertEquals(409, submit(http, id, firstQuestion, "another answer", "wrong-bundle"));
                EVALUATION_REPLY.set(new Reply(200, evaluation(EVIDENCE, "Second evaluation.")));
                assertEquals(202, submit(http, id, second.at("/currentQuestion/id").asText(), "second answer", "bundle-two"));
                var finished = awaitEvaluation(http, id, "READY");
                assertEquals(2, finished.path("completedBundles").asInt());
                assertEquals("first answer", finished.at("/completedBundleHistory/0/answers/0/answer").asText());
                assertEquals("Second evaluation.", finished.at("/completedBundleHistory/1/evaluation/strengths").asText());
                var input = JSON.readTree(EVALUATION_REQUEST.get().at("/messages/1/content").asText());
                assertEquals(1, input.path("turns").size());
                assertEquals("second answer", input.at("/turns/0/answer").asText());

                var third = next(http, id, successor, "JAVA");
                assertEquals(202, third.statusCode(), third.body());
                var lateReplay = next(http, id, previous, "RANDOM");
                assertEquals(202, lateReplay.statusCode(), lateReplay.body());
                assertEquals(successor, JSON.readTree(lateReplay.body()).path("bundleId").asText());
                assertEquals(1, SNAPSHOT_CALLS.get());
            }
        }

        @Test
        void scheduledEvaluationPublishesOnlyAfterNormalStop() throws Exception {
            port = scheduledPort;
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            EVALUATION_REPLY.set(new Reply(200, evaluation(EVIDENCE, "Clear trade-off."), entered, release));
            FOLLOW_UP_REPLY.set(new Reply(200, outcome("STOP", "", EVIDENCE)));
            try (var http = HttpClient.newHttpClient()) {
                UUID id = start(http, scheduledDecoder);
                String question = detail(http, id).at("/currentQuestion/id").asText();
                assertFalse(detail(http, id).has("evaluationStatus"));
                assertEquals(202, submit(http, id, question, "I chose simplicity.", "evaluated"));
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS));
                    var pending = detail(http, id);
                    assertEquals("QUESTIONS_COMPLETE", pending.path("answerProgress").asText());
                    assertEquals("PENDING", pending.path("evaluationStatus").asText());
                    assertEquals(0, pending.path("completedBundles").asInt());
                    assertFalse(pending.has("evaluation"));
                    assertEquals(202, submit(http, id, question, "I chose simplicity.", "evaluated"));
                } finally {
                    release.countDown();
                }
                var published = awaitEvaluation(http, id, "READY");
                assertEquals(1, published.path("completedBundles").asInt());
                assertEquals("Clear trade-off.", published.at("/evaluation/strengths").asText());
                var request = EVALUATION_REQUEST.get();
                assertEquals(EVIDENCE.toString(), request.at("/format/properties/evidenceId/enum/0").asText());
                var data = JSON.readTree(request.at("/messages/1/content").asText());
                assertEquals(SUMMARY, data.path("codeFact").asText());
                assertEquals(question, data.at("/turns/0/questionId").asText());
                assertEquals("I chose simplicity.", data.at("/turns/0/answer").asText());
            } finally {
                release.countDown();
            }
        }

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

    @Nested
    @TestPropertySource(properties = {
            "devfield.interview.evaluation.enabled=true",
            "devfield.interview.evaluation.poll-ms=3600000",
            "devfield.interview.first-question.poll-ms=3600000",
            "devfield.interview.next-bundle.enabled=true",
            "devfield.interview.lifecycle.enabled=true",
            "devfield.interview.session-deletion.enabled=true"
    })
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class EvaluationRaces {
        @LocalServerPort int evaluationPort;
        @MockitoBean JwtDecoder evaluationDecoder;
        @Autowired BundleEvaluationWorker worker;
        @Autowired FollowUpWorker closingWorker;
        @Autowired FirstQuestionWorker mainWorker;
        @Autowired InterviewSessions interviewSessions;

        @Test
        void thirdFollowUpClosesBundleAndQueuesOnce() throws Exception {
            port = evaluationPort;
            try (var http = HttpClient.newHttpClient()) {
                UUID id = start(http, evaluationDecoder, mainWorker::poll);
                for (int i = 0; i < 4; i++) {
                    String question = detail(http, id).at("/currentQuestion/id").asText();
                    assertEquals(202, submit(http, id, question, "answer " + i, "turn-" + i));
                    if (i < 3) {
                        assertFalse(detail(http, id).has("evaluationStatus"));
                        FOLLOW_UP_REPLY.set(new Reply(200, outcome("FOLLOW_UP", "Trade-off " + i + "?", EVIDENCE)));
                        closingWorker.poll();
                    } else {
                        assertEquals(202, submit(http, id, question, "answer " + i, "turn-" + i));
                    }
                }
                assertEquals(1, evaluationCount(id));
                assertEquals("PENDING", detail(http, id).path("evaluationStatus").asText());
                EVALUATION_REPLY.set(new Reply(200, evaluation(EVIDENCE, "Refined reasoning.")));
                worker.poll();
                assertEquals("READY", detail(http, id).path("evaluationStatus").asText());
                var input = JSON.readTree(EVALUATION_REQUEST.get().at("/messages/1/content").asText());
                assertEquals(4, input.path("turns").size());
                assertEquals("answer 3", input.at("/turns/3/answer").asText());
            }
        }

        @ParameterizedTest @ValueSource(booleans = {false, true})
        void evaluationFailurePreservesAnswers(boolean transientFailure) throws Exception {
            port = evaluationPort;
            try (var http = HttpClient.newHttpClient()) {
                UUID id = closeNormally(http);
                EVALUATION_REPLY.set(transientFailure ? new Reply(503, null)
                        : new Reply(200, evaluation(UUID.randomUUID(), "Unsupported evidence.")));
                int attempts = transientFailure ? 3 : 1;
                for (int attempt = 1; attempt <= attempts; attempt++) {
                    worker.poll();
                    assertEquals(attempt, db.queryForObject(
                            "SELECT attempt FROM interview.bundle_evaluation WHERE session_id=?", Integer.class, id));
                    var detail = detail(http, id);
                    assertEquals(attempt < attempts ? "PENDING" : "FAILED", detail.path("evaluationStatus").asText());
                    assertFalse(detail.has("evaluation"));
                    assertEquals(0, detail.path("completedBundles").asInt());
                    assertEquals("saved answer", detail.at("/answers/0/answer").asText());
                    db.update("UPDATE interview.bundle_evaluation SET due_at=clock_timestamp() WHERE session_id=?", id);
                }
                worker.poll();
                assertEquals("FAILED", detail(http, id).path("evaluationStatus").asText());
            }
        }

        @ParameterizedTest @ValueSource(booleans = {false, true})
        void deletionCascadesPublishedEvaluationAndFencesLateResult(boolean userDeletion) throws Exception {
            port = evaluationPort;
            try (var http = HttpClient.newHttpClient(); var executor = Executors.newSingleThreadExecutor()) {
                UUID published = closeNormally(http);
                EVALUATION_REPLY.set(new Reply(200, evaluation(EVIDENCE, "Published.")));
                worker.poll();
                assertEquals("READY", detail(http, published).path("evaluationStatus").asText());

                UUID late = closeNormally(http);
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                EVALUATION_REPLY.set(new Reply(200, evaluation(EVIDENCE, "Late result."), entered, release));
                String blocker = "interview.test_evaluation_" + late.toString().replace("-", "");
                UUID job = UUID.randomUUID();
                db.execute("CREATE TABLE " + blocker + " (id uuid REFERENCES interview.interview_session(id))");
                try {
                    db.update("INSERT INTO " + blocker + " VALUES (?)", late);
                    var running = executor.submit(worker::poll);
                    try {
                        assertTrue(entered.await(5, TimeUnit.SECONDS));
                        assertEquals(503, delete(http, userDeletion, job));
                        for (UUID id : List.of(published, late)) {
                            assertEquals(404, http.send(request("/api/interviews/" + id).GET().build(),
                                    HttpResponse.BodyHandlers.discarding()).statusCode());
                        }
                    } finally {
                        release.countDown();
                    }
                    running.get(10, TimeUnit.SECONDS);
                    assertEquals("RUNNING", db.queryForObject(
                            "SELECT state FROM interview.bundle_evaluation WHERE session_id=?", String.class, late));
                    assertNull(db.queryForObject(
                            "SELECT result::text FROM interview.bundle_evaluation WHERE session_id=?", String.class, late));
                } finally {
                    db.execute("DROP TABLE " + blocker);
                }
                assertEquals(204, delete(http, userDeletion, job));
                assertEquals(0, evaluationCount(published));
                assertEquals(0, evaluationCount(late));
            }
        }

        @Test
        void expiredAttemptCannotPublishWhileReplacementRuns() throws Exception {
            port = evaluationPort;
            try (var http = HttpClient.newHttpClient(); var executor = Executors.newFixedThreadPool(2)) {
                UUID id = closeNormally(http);
                var firstEntered = new CountDownLatch(1);
                var firstRelease = new CountDownLatch(1);
                var secondEntered = new CountDownLatch(1);
                var secondRelease = new CountDownLatch(1);
                EVALUATION_REPLY.set(new Reply(200, evaluation(EVIDENCE, "Expired."), firstEntered, firstRelease));
                var first = executor.submit(worker::poll);
                try {
                    assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
                    db.update("""
                            UPDATE interview.bundle_evaluation SET due_at=clock_timestamp(),lease_until=clock_timestamp()
                            WHERE session_id=?
                            """, id);
                    EVALUATION_REPLY.set(new Reply(200, evaluation(EVIDENCE, "Replacement."), secondEntered, secondRelease));
                    var second = executor.submit(worker::poll);
                    assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
                    firstRelease.countDown();
                    first.get(10, TimeUnit.SECONDS);
                    var pending = detail(http, id);
                    assertEquals("PENDING", pending.path("evaluationStatus").asText());
                    assertFalse(pending.has("evaluation"));
                    secondRelease.countDown();
                    second.get(10, TimeUnit.SECONDS);
                    assertEquals("Replacement.", detail(http, id).at("/evaluation/strengths").asText());
                } finally {
                    firstRelease.countDown();
                    secondRelease.countDown();
                }
            }
        }

        @ParameterizedTest @ValueSource(booleans = {false, true})
        void deletionFencesNextBundleAndLateMainQuestion(boolean userDeletion) throws Exception {
            port = evaluationPort;
            try (var http = HttpClient.newHttpClient(); var executor = Executors.newSingleThreadExecutor()) {
                UUID id = closeNormally(http);
                EVALUATION_REPLY.set(new Reply(200, evaluation(EVIDENCE, "Prior evaluation.")));
                worker.poll();
                String predecessor = detail(http, id).path("currentBundleId").asText();
                var accepted = next(http, id, predecessor, "JAVA");
                assertEquals(202, accepted.statusCode(), accepted.body());
                UUID successor = UUID.fromString(JSON.readTree(accepted.body()).path("bundleId").asText());

                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                MAIN_REPLY.set(new Reply(200, OllamaFirstQuestionGeneratorTest.content(EVIDENCE), entered, release));
                String blocker = "interview.test_next_bundle_" + id.toString().replace("-", "");
                UUID job = UUID.randomUUID();
                db.execute("CREATE TABLE " + blocker + " (id uuid REFERENCES interview.interview_session(id))");
                try {
                    db.update("INSERT INTO " + blocker + " VALUES (?)", id);
                    var running = executor.submit(mainWorker::poll);
                    try {
                        assertTrue(entered.await(5, TimeUnit.SECONDS));
                        assertEquals(503, delete(http, userDeletion, job));
                        assertEquals(409, next(http, id, predecessor, "JAVA").statusCode());
                        assertEquals(409, next(http, id, successor.toString(), "JAVA").statusCode());
                        assertEquals(404, http.send(request("/api/interviews/" + id).GET().build(),
                                HttpResponse.BodyHandlers.discarding()).statusCode());
                    } finally {
                        release.countDown();
                    }
                    running.get(10, TimeUnit.SECONDS);
                    assertEquals(0, db.queryForObject("SELECT count(*) FROM interview.question_turn WHERE bundle_id=?",
                            Integer.class, successor));
                } finally {
                    db.execute("DROP TABLE " + blocker);
                }
                assertEquals(204, delete(http, userDeletion, job));
                assertEquals(0, db.queryForObject("SELECT count(*) FROM interview.question_bundle WHERE session_id=?",
                        Integer.class, id));
                assertEquals(0, evaluationCount(id));
            }
        }

        @ParameterizedTest @EnumSource(GenerationStage.class)
        void quickPauseResumeFencesTheEarlierAttempt(GenerationStage stage) throws Exception {
            port = evaluationPort;
            try (var http = HttpClient.newHttpClient(); var executor = Executors.newFixedThreadPool(2)) {
                var work = prepareGeneration(http, stage);
                UUID id = work.session();
                var firstEntered = new CountDownLatch(1);
                var firstRelease = new CountDownLatch(1);
                var secondEntered = new CountDownLatch(1);
                var secondRelease = new CountDownLatch(1);
                work.reply().set(new Reply(200, stage.response("Old result."), firstEntered, firstRelease));
                var first = executor.submit(work.poll());
                try {
                    assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
                    assertLifecycle(http, id, "PAUSE", "PAUSED");
                    assertLifecycle(http, id, "PAUSE", "PAUSED");
                    assertEquals("PAUSED", detail(http, id).path("status").asText());
                    assertWork(work, "QUEUED", 1, false);
                    work.poll().run();
                    assertWork(work, "QUEUED", 1, false);
                    assertLifecycle(http, id, "RESUME", stage == GenerationStage.MAIN ? "PREPARING" : "ACTIVE");
                    work.reply().set(new Reply(200, stage.response("New result."), secondEntered, secondRelease));
                    var second = executor.submit(work.poll());
                    assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
                    firstRelease.countDown();
                    first.get(10, TimeUnit.SECONDS);
                    assertWork(work, "RUNNING", 2, true);
                    assertFalse(detail(http, id).has(stage == GenerationStage.EVALUATION ? "evaluation" : "currentQuestion"));
                    secondRelease.countDown();
                    second.get(10, TimeUnit.SECONDS);
                    assertEquals("New result.", detail(http, id).at(stage.resultPath()).asText());
                } finally {
                    firstRelease.countDown();
                    secondRelease.countDown();
                }
            }
        }

        @Test
        void pausingLastAttemptDoesNotRestoreRetryBudget() throws Exception {
            port = evaluationPort;
            try (var http = HttpClient.newHttpClient(); var executor = Executors.newSingleThreadExecutor()) {
                var work = prepareGeneration(http, GenerationStage.MAIN);
                UUID id = work.session();
                db.update("UPDATE interview.first_question_job SET attempt=2 WHERE session_id=?", id);
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                work.reply().set(new Reply(200, work.stage().response("Ignored last attempt."), entered, release));
                var running = executor.submit(work.poll());
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    assertLifecycle(http, id, "PAUSE", "PAUSED");
                    assertWork(work, "QUEUED", 3, false);
                    assertLifecycle(http, id, "RESUME", "PREPARING");
                } finally {
                    release.countDown();
                }
                running.get(10, TimeUnit.SECONDS);
                work.poll().run();
                assertWork(work, "FAILED", 3, false);
                assertEquals("GENERATION_FAILED", detail(http, id).path("status").asText());
                assertLifecycle(http, id, "PAUSE", "PAUSED");
                assertLifecycle(http, id, "RESUME", "GENERATION_FAILED");
                assertLifecycle(http, id, "PAUSE", "PAUSED");
                assertLifecycle(http, id, "END", "ENDED");
            }
        }

        @ParameterizedTest
        @CsvSource({"MAIN,false", "MAIN,true", "FOLLOW_UP,false", "FOLLOW_UP,true", "EVALUATION,false", "EVALUATION,true"})
        void deletionBlocksLifecycleAndLateResults(GenerationStage stage, boolean userDeletion) throws Exception {
            port = evaluationPort;
            try (var http = HttpClient.newHttpClient(); var executor = Executors.newSingleThreadExecutor()) {
                var work = prepareGeneration(http, stage);
                UUID id = work.session();
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                work.reply().set(new Reply(200, stage.response("Late result."), entered, release));
                String blocker = "interview.test_lifecycle_" + id.toString().replace("-", "");
                UUID job = UUID.randomUUID();
                db.execute("CREATE TABLE " + blocker + " (id uuid REFERENCES interview.interview_session(id))");
                try {
                    db.update("INSERT INTO " + blocker + " VALUES (?)", id);
                    var running = executor.submit(work.poll());
                    try {
                        assertTrue(entered.await(5, TimeUnit.SECONDS));
                        assertWork(work, "RUNNING", 1, true);
                        assertEquals(503, delete(http, userDeletion, job));
                        assertEquals("DELETING", db.queryForObject("""
                                SELECT state FROM interview.interview_write_fence WHERE deletion_job_id=? AND scope=?
                                """, String.class, job, userDeletion ? "USER" : "PROJECT"));
                        for (String action : List.of("PAUSE", "RESUME", "END")) {
                            assertEquals(409, lifecycle(http, id, action).statusCode());
                        }
                        assertEquals(404, http.send(request("/api/interviews/" + id).GET().build(),
                                HttpResponse.BodyHandlers.discarding()).statusCode());
                        assertEquals("OPEN", db.queryForObject(
                                "SELECT lifecycle FROM interview.interview_session WHERE id=?", String.class, id));
                        assertWork(work, "RUNNING", 1, true);
                    } finally {
                        release.countDown();
                    }
                    running.get(10, TimeUnit.SECONDS);
                    assertWork(work, "RUNNING", 1, true);
                } finally {
                    db.execute("DROP TABLE " + blocker);
                }
                assertEquals(204, delete(http, userDeletion, job));
                assertEquals(0, db.queryForObject("SELECT count(*) FROM interview." + stage.table + " WHERE session_id=?",
                        Integer.class, id));
            }
        }

        @Test
        void endingPreservesRecordsAndRejectsNewWritesButAllowsAcceptedReplays() throws Exception {
            port = evaluationPort;
            try (var http = HttpClient.newHttpClient(); var executor = Executors.newSingleThreadExecutor()) {
                UUID id = closeNormally(http);
                String firstQuestion = detail(http, id).at("/answers/0/questionId").asText();
                EVALUATION_REPLY.set(new Reply(200, evaluation(EVIDENCE, "First evaluation.")));
                worker.poll();
                String predecessor = detail(http, id).path("currentBundleId").asText();
                assertLifecycle(http, id, "PAUSE", "PAUSED");
                assertEquals(409, next(http, id, predecessor, "JAVA").statusCode());
                assertLifecycle(http, id, "RESUME", "ACTIVE");
                var accepted = next(http, id, predecessor, "JAVA");
                assertEquals(202, accepted.statusCode(), accepted.body());
                mainWorker.poll();
                String question = detail(http, id).at("/currentQuestion/id").asText();
                assertLifecycle(http, id, "PAUSE", "PAUSED");
                assertEquals(409, submit(http, id, question, "not accepted", "new"));
                assertLifecycle(http, id, "RESUME", "ACTIVE");
                assertEquals(202, submit(http, id, question, "kept answer", "second"));
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                FOLLOW_UP_REPLY.set(new Reply(200, GenerationStage.FOLLOW_UP.response("Late after END."), entered, release));
                var running = executor.submit(closingWorker::poll);
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    assertLifecycle(http, id, "END", "ENDED");
                    assertLifecycle(http, id, "END", "ENDED");
                } finally {
                    release.countDown();
                }
                running.get(10, TimeUnit.SECONDS);

                assertEquals(409, lifecycle(http, id, "RESUME").statusCode());
                assertEquals(409, lifecycle(http, id, "PAUSE").statusCode());
                assertEquals(409, submit(http, id, question, "changed", "third"));
                assertEquals(202, submit(http, id, question, "kept answer", "second"));
                assertEquals(202, submit(http, id, firstQuestion, "saved answer", "evaluation"));
                var replay = next(http, id, predecessor, "JAVA");
                assertEquals(202, replay.statusCode());
                assertEquals(JSON.readTree(accepted.body()), JSON.readTree(replay.body()));

                var ended = detail(http, id);
                assertEquals("ENDED", ended.path("status").asText());
                assertEquals("FOLLOW_UP_CANCELLED", ended.path("answerProgress").asText());
                assertFalse(ended.has("currentQuestion"));
                assertEquals("kept answer", ended.at("/answers/0/answer").asText());
                assertEquals("First evaluation.", ended.at("/completedBundleHistory/0/evaluation/strengths").asText());
                assertEquals(1, ended.path("completedBundles").asInt());
            }
        }

        @Test
        void deletesOnlyTheOwnersFreeSessionAndAllOfItsBundles() throws Exception {
            port = evaluationPort;
            try (var http = HttpClient.newHttpClient()) {
                UUID sibling = start(http, evaluationDecoder, mainWorker::poll);
                var snapshot = interviewSessions.find(OWNER, sibling).orElseThrow().snapshot();
                UUID mock = interviewSessions.create(OWNER, PROJECT, InterviewSessions.Mode.MOCK, snapshot).id();

                UUID id = closeNormally(http);
                EVALUATION_REPLY.set(new Reply(200, evaluation(EVIDENCE, "Saved evaluation.")));
                worker.poll();
                String previous = detail(http, id).path("currentBundleId").asText();
                assertEquals(202, next(http, id, previous, "JAVA").statusCode());
                mainWorker.poll();
                String question = detail(http, id).at("/currentQuestion/id").asText();
                assertEquals(202, submit(http, id, question, "second answer", "bundle-two"));
                closingWorker.poll();
                worker.poll();
                assertEquals(2, detail(http, id).path("completedBundles").asInt());
                assertLifecycle(http, id, "PAUSE", "PAUSED");
                assertLifecycle(http, id, "END", "ENDED");

                UUID outsider = UUID.randomUUID();
                String outsiderToken = "fixture." + outsider + ".signature";
                when(evaluationDecoder.decode(outsiderToken)).thenReturn(Jwt.withTokenValue(outsiderToken)
                        .header("alg", "RS256").subject(outsider.toString()).issuedAt(Instant.now().minusSeconds(1))
                        .expiresAt(Instant.now().plusSeconds(120)).build());
                var foreign = http.send(request("/api/interviews/" + id)
                        .setHeader("Authorization", "Bearer " + outsiderToken).DELETE().build(),
                        HttpResponse.BodyHandlers.ofString());
                var missing = deleteSession(http, UUID.randomUUID());
                assertEquals(404, foreign.statusCode());
                assertEquals(404, missing.statusCode());
                assertEquals(JSON.readTree(missing.body()), JSON.readTree(foreign.body()));
                assertEquals(404, deleteSession(http, mock).statusCode());
                assertEquals(2, evaluationCount(id));

                var deleted = deleteSession(http, id);
                assertEquals(204, deleted.statusCode(), deleted.body());
                assertTrue(deleted.body().isEmpty());
                assertEquals(404, deleteSession(http, id).statusCode());
                assertEquals(404, http.send(request("/api/interviews/" + id).GET().build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode());
                assertSessionDataGone(id);
                assertEquals("ACTIVE", detail(http, sibling).path("status").asText());
                assertEquals("MOCK", db.queryForObject(
                        "SELECT mode FROM interview.interview_session WHERE id=?", String.class, mock));
                assertEquals(2, db.queryForObject("""
                        SELECT count(*) FROM interview.interview_write_fence WHERE owner_user_id=? AND state='ACTIVE'
                        """, Integer.class, OWNER));
            }
        }

        @ParameterizedTest @EnumSource(GenerationStage.class)
        void sessionDeletionFencesLateResultsWithAValidLease(GenerationStage stage) throws Exception {
            port = evaluationPort;
            try (var http = HttpClient.newHttpClient(); var executor = Executors.newSingleThreadExecutor()) {
                var work = prepareGeneration(http, stage);
                UUID id = work.session();
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                work.reply().set(new Reply(200, stage.response("Late result."), entered, release));
                var running = executor.submit(work.poll());
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    assertWork(work, "RUNNING", 1, true);
                    assertEquals(204, deleteSession(http, id).statusCode());
                    assertSessionDataGone(id);
                } finally {
                    release.countDown();
                }
                running.get(10, TimeUnit.SECONDS);
                assertSessionDataGone(id);
            }
        }

        @ParameterizedTest
        @CsvSource({"false,false", "false,true", "true,false", "true,true"})
        void sessionAndScopeDeletionWaitForTheSharedFence(boolean userDeletion, boolean scopeFirst) throws Exception {
            port = evaluationPort;
            try (var http = HttpClient.newHttpClient()) {
                UUID id = closeNormally(http);
                UUID job = UUID.randomUUID();
                Callable<Integer> individual = () -> deleteSession(http, id).statusCode();
                Callable<Integer> scope = () -> delete(http, userDeletion, job);
                if (scopeFirst) {
                    String blocker = "interview.test_individual_delete_" + id.toString().replace("-", "");
                    db.execute("CREATE TABLE " + blocker + " (id uuid REFERENCES interview.interview_session(id))");
                    try {
                        db.update("INSERT INTO " + blocker + " VALUES (?)", id);
                        overlapDeletions("interview_write_fence", "UPDATE", "NEW.deletion_job_id = '" + job + "'",
                                scope, individual, 503, 409);
                        assertEquals("DELETING", db.queryForObject("""
                                SELECT state FROM interview.interview_write_fence WHERE deletion_job_id=? AND scope=?
                                """, String.class, job, userDeletion ? "USER" : "PROJECT"));
                        assertEquals(1, evaluationCount(id));
                    } finally {
                        db.execute("DROP TABLE " + blocker);
                    }
                    assertEquals(204, scope.call());
                } else {
                    overlapDeletions("interview_session", "DELETE", "OLD.id = '" + id + "'",
                            individual, scope, 204, 204);
                }
                assertEquals(404, individual.call());
                assertSessionDataGone(id);
            }
        }

        private void overlapDeletions(String table, String operation, String predicate, Callable<Integer> first,
                                      Callable<Integer> second, int firstStatus, int secondStatus) throws Exception {
            String gate = "test_delete_gate_" + UUID.randomUUID().toString().replace("-", "");
            long key = UUID.randomUUID().getLeastSignificantBits();
            // Hold the leading request inside its transaction, after it acquired the write fences.
            db.execute("""
                    CREATE FUNCTION interview.%s() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        PERFORM pg_advisory_xact_lock(%d);
                        RETURN %s;
                    END;
                    $$
                    """.formatted(gate, key, operation.equals("DELETE") ? "OLD" : "NEW"));
            try {
                db.execute("""
                        CREATE TRIGGER %s BEFORE %s ON interview.%s FOR EACH ROW
                        WHEN (%s) EXECUTE FUNCTION interview.%s()
                        """.formatted(gate, operation, table, predicate, gate));
                try (var executor = Executors.newFixedThreadPool(2);
                     var gateConnection = db.getDataSource().getConnection();
                     var statement = gateConnection.createStatement()) {
                    int gatePid;
                    try (var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
                        assertTrue(rows.next());
                        gatePid = rows.getInt(1);
                    }
                    statement.execute("SELECT pg_advisory_lock(" + key + ")");
                    var leading = executor.submit(first);
                    java.util.concurrent.Future<Integer> trailing;
                    try {
                        int leadingPid = awaitBlockedBy(gatePid);
                        trailing = executor.submit(second);
                        awaitBlockedBy(leadingPid);
                    } finally {
                        statement.execute("SELECT pg_advisory_unlock(" + key + ")");
                    }
                    assertEquals(firstStatus, leading.get(10, TimeUnit.SECONDS));
                    assertEquals(secondStatus, trailing.get(10, TimeUnit.SECONDS));
                } finally {
                    db.execute("DROP TRIGGER IF EXISTS " + gate + " ON interview." + table);
                }
            } finally {
                db.execute("DROP FUNCTION interview." + gate + "()");
            }
        }

        private int awaitBlockedBy(int blockerPid) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
            do {
                var blocked = db.queryForList("""
                        SELECT pid FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid))
                        """, Integer.class, blockerPid);
                if (!blocked.isEmpty()) return blocked.getFirst();
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("No request blocked on PostgreSQL backend " + blockerPid);
        }

        private void assertSessionDataGone(UUID id) {
            assertEquals(0, db.queryForObject("SELECT count(*) FROM interview.interview_session WHERE id=?",
                    Integer.class, id));
            for (String table : List.of("question_bundle", "question_turn", "user_answer",
                    "bundle_evaluation", "first_question_job")) {
                assertEquals(0, db.queryForObject("SELECT count(*) FROM interview." + table + " WHERE session_id=?",
                        Integer.class, id), table);
            }
        }

        private GenerationWork prepareGeneration(HttpClient http, GenerationStage stage) throws Exception {
            return switch (stage) {
                case MAIN -> new GenerationWork(createSession(http, evaluationDecoder), mainWorker::poll, MAIN_REPLY, stage);
                case FOLLOW_UP -> {
                    UUID id = start(http, evaluationDecoder, mainWorker::poll);
                    String question = detail(http, id).at("/currentQuestion/id").asText();
                    assertEquals(202, submit(http, id, question, "saved answer", "follow"));
                    yield new GenerationWork(id, closingWorker::poll, FOLLOW_UP_REPLY, stage);
                }
                case EVALUATION -> new GenerationWork(closeNormally(http), worker::poll, EVALUATION_REPLY, stage);
            };
        }

        private void assertWork(GenerationWork work, String state, int attempt, boolean leased) {
            var row = db.queryForMap("""
                    SELECT state,attempt,COALESCE(lease_until > clock_timestamp(), false) AS leased
                    FROM interview.%s WHERE session_id=?
                    """.formatted(work.stage().table), work.session());
            assertEquals(Map.of("state", state, "attempt", attempt, "leased", leased), row);
        }

        private UUID closeNormally(HttpClient http) throws Exception {
            UUID id = start(http, evaluationDecoder, mainWorker::poll);
            String question = detail(http, id).at("/currentQuestion/id").asText();
            assertEquals(202, submit(http, id, question, "saved answer", "evaluation"));
            FOLLOW_UP_REPLY.set(new Reply(200, outcome("STOP", "", EVIDENCE)));
            closingWorker.poll();
            assertEquals("PENDING", detail(http, id).path("evaluationStatus").asText());
            return id;
        }
    }

    @Test
    void scheduledEvaluationResumesAfterContextRestart() throws Exception {
        JwtDecoder restartDecoder = mock(JwtDecoder.class);
        FOLLOW_UP_REPLY.set(new Reply(200, outcome("STOP", "", EVIDENCE)));
        EVALUATION_REPLY.set(new Reply(200, evaluation(EVIDENCE, "Recovered after restart.")));

        try (var http = HttpClient.newHttpClient()) {
            UUID id;
            try (var before = startEvaluationContext(restartDecoder, 3_600_000)) {
                port = ((WebServerApplicationContext) before).getWebServer().getPort();
                id = start(http, restartDecoder);
                String question = detail(http, id).at("/currentQuestion/id").asText();
                assertEquals(202, submit(http, id, question, "Persisted answer.", "restart"));
                var pending = awaitEvaluation(http, id, "PENDING");
                assertFalse(pending.has("evaluation"));
            }

            assertEquals("QUEUED", db.queryForObject("""
                    SELECT state FROM interview.bundle_evaluation WHERE session_id=?
                    """, String.class, id));
            assertNull(EVALUATION_REQUEST.get());

            try (var after = startEvaluationContext(restartDecoder, 100)) {
                port = ((WebServerApplicationContext) after).getWebServer().getPort();
                var published = awaitEvaluation(http, id, "READY");
                assertEquals(1, published.path("completedBundles").asInt());
                assertEquals("Recovered after restart.", published.at("/evaluation/strengths").asText());
                assertEquals("Persisted answer.", published.at("/answers/0/answer").asText());
                var sent = EVALUATION_REQUEST.get();
                assertNotNull(sent);
                var input = JSON.readTree(sent.at("/messages/1/content").asText());
                assertEquals(EVIDENCE.toString(), input.path("evidenceId").asText());
                assertEquals("Persisted answer.", input.at("/turns/0/answer").asText());
            }
        }
    }

    private ConfigurableApplicationContext startEvaluationContext(JwtDecoder jwtDecoder, int pollMillis) {
        var environment = context.getEnvironment();
        return new SpringApplicationBuilder(InterviewApplication.class)
                .registerShutdownHook(false)
                .initializers(application -> ((GenericApplicationContext) application)
                        .registerBean("restartTestJwtDecoder", JwtDecoder.class, () -> jwtDecoder,
                                definition -> definition.setPrimary(true)))
                .run("--server.port=0",
                        "--devfield.interview.sessions.enabled=true",
                        "--devfield.interview.first-question.fake-enabled=false",
                        "--devfield.interview.first-question.ollama-enabled=true",
                        "--devfield.interview.first-question.ollama-port=" + upstream.getAddress().getPort(),
                        "--devfield.interview.first-question.poll-ms=100",
                        "--devfield.interview.follow-up.enabled=true",
                        "--devfield.interview.follow-up.poll-ms=100",
                        "--devfield.interview.evaluation.enabled=true",
                        "--devfield.interview.evaluation.poll-ms=" + pollMillis,
                        "--devfield.interview.auth.session-check-uri="
                                + environment.getRequiredProperty("devfield.interview.auth.session-check-uri"),
                        "--devfield.interview.auth.service-key="
                                + environment.getRequiredProperty("devfield.interview.auth.service-key"),
                        "--devfield.interview.project.base-url="
                                + environment.getRequiredProperty("devfield.interview.project.base-url"),
                        "--devfield.interview.project.service-key="
                                + environment.getRequiredProperty("devfield.interview.project.service-key"));
    }

    private enum GenerationStage {
        MAIN("first_question_job"), FOLLOW_UP("user_answer"), EVALUATION("bundle_evaluation");

        private final String table;
        GenerationStage(String table) { this.table = table; }

        String response(String text) {
            return switch (this) {
                case MAIN -> JSON.valueToTree(Map.of("question", text, "evidenceId", EVIDENCE)).toString();
                case FOLLOW_UP -> outcome("FOLLOW_UP", text, EVIDENCE);
                case EVALUATION -> evaluation(EVIDENCE, text);
            };
        }

        String resultPath() {
            return this == EVALUATION ? "/evaluation/strengths" : "/currentQuestion/text";
        }
    }

    private record GenerationWork(UUID session, Runnable poll, AtomicReference<Reply> reply, GenerationStage stage) {}

    private int evaluationCount(UUID id) {
        return db.queryForObject("SELECT count(*) FROM interview.bundle_evaluation WHERE session_id=?", Integer.class, id);
    }

    private JsonNode awaitEvaluation(HttpClient http, UUID id, String status) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        JsonNode result;
        do {
            result = detail(http, id);
            if (status.equals(result.path("evaluationStatus").asText())) return result;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Evaluation did not reach " + status + ": " + result);
    }

    private static String evaluation(UUID evidence, String strengths) {
        return JSON.valueToTree(Map.of("evidenceId", evidence, "strengths", strengths,
                "gaps", "Discuss the alternative.", "improvements", "Compare maintenance costs.",
                "exampleAnswer", "I chose a simpler implementation.", "keyPoints", "State the trade-off.",
                "limitations", "The supplied code does not establish performance.")).toString();
    }

    private static String outcome(String action, String question, UUID evidence) {
        return JSON.valueToTree(Map.of("action", action, "question", question, "evidenceId", evidence)).toString();
    }

    private UUID start(HttpClient http) throws Exception {
        return start(http, decoder);
    }

    private UUID start(HttpClient http, JwtDecoder jwtDecoder) throws Exception {
        return start(http, jwtDecoder, () -> {});
    }

    private UUID start(HttpClient http, JwtDecoder jwtDecoder, Runnable afterSubmit) throws Exception {
        UUID id = createSession(http, jwtDecoder);
        afterSubmit.run();
        awaitActive(http, id);
        return id;
    }

    private UUID createSession(HttpClient http, JwtDecoder jwtDecoder) throws Exception {
        when(jwtDecoder.decode(TOKEN)).thenReturn(Jwt.withTokenValue(TOKEN).header("alg", "RS256")
                .subject(OWNER.toString()).issuedAt(Instant.now().minusSeconds(1))
                .expiresAt(Instant.now().plusSeconds(120)).build());
        var response = http.send(request("/api/interviews").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
                        Map.of("projectId", PROJECT, "mode", "FREE", "topicKey", "JAVA")))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), response.body());
        return UUID.fromString(JSON.readTree(response.body()).path("interviewId").asText());
    }

    private JsonNode awaitActive(HttpClient http, UUID id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        do {
            var result = detail(http, id);
            if ("ACTIVE".equals(result.path("status").asText())) return result;
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

    private HttpResponse<String> next(HttpClient http, UUID id, String previous, String topic) throws Exception {
        return http.send(request("/api/interviews/" + id + "/bundles").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of(
                        "previousBundleId", previous, "topicKey", topic)))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> lifecycle(HttpClient http, UUID id, String action) throws Exception {
        return http.send(request("/api/interviews/" + id).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("action", action))))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertLifecycle(HttpClient http, UUID id, String action, String expected) throws Exception {
        var response = lifecycle(http, id, action);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(expected, JSON.readTree(response.body()).path("status").asText());
    }

    private HttpResponse<String> deleteSession(HttpClient http, UUID id) throws Exception {
        return http.send(request("/api/interviews/" + id).DELETE().build(), HttpResponse.BodyHandlers.ofString());
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
