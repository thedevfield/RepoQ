package dev.devfield.repoq.interview.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.devfield.repoq.interview.project.ProjectSnapshot;
import dev.devfield.repoq.interview.project.ProjectSnapshotClient;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "devfield.interview.sessions.enabled=true",
        "devfield.interview.first-question.fake-enabled=true",
        "devfield.interview.first-question.poll-ms=3600000",
        "devfield.interview.deletion.project-key-sha256=4acb595e5ada8c9a973db7268d56e9ed27bc097157ce781b7ef0ce5ef401e95d",
        "devfield.interview.deletion.auth-key-sha256=9dc1622a4d732fd2b95d9733ff02dbdf1364f60465c4d10a8badcf779cab4c3e"
})
class InterviewSessionIntegrationTest {
    private static final String AUTH_KEY = "auth-check-test-credential";
    private static final String X01_KEY = "x01-read-only-test-credential-012345678";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicReference<Object> SNAPSHOT = new AtomicReference<>();
    private static final AtomicInteger PROJECT_STATUS = new AtomicInteger(200);
    private static final AtomicInteger PROJECT_CALLS = new AtomicInteger();
    private static final AtomicReference<String> PROJECT_BEARER = new AtomicReference<>();
    private static final AtomicReference<JsonNode> OLLAMA_REQUEST = new AtomicReference<>();
    private static final AtomicInteger OLLAMA_STATUS = new AtomicInteger(200);
    private static final AtomicReference<UUID> OLLAMA_RESPONSE_EVIDENCE = new AtomicReference<>();
    private static HttpServer upstream;

    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired FirstQuestionWorker worker;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @MockitoSpyBean FakeFirstQuestionGenerator generator;
    @MockitoBean JwtDecoder decoder;
    @MockitoSpyBean ProjectSnapshotClient snapshots;
    @MockitoSpyBean InterviewSessions sessions;

    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID();
    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/internal/auth/session-check", exchange -> {
            try (exchange) {
                String token = exchange.getRequestHeaders().getFirst("Authorization");
                int status = "POST".equals(exchange.getRequestMethod())
                        && "interview".equals(exchange.getRequestHeaders().getFirst("X-DevField-Service"))
                        && AUTH_KEY.equals(exchange.getRequestHeaders().getFirst("X-DevField-Service-Key"))
                        && token != null && token.startsWith("Bearer ") ? switch (token.split("\\.")[0]) {
                            case "Bearer expired" -> 401;
                            case "Bearer deleting" -> 403;
                            case "Bearer outage" -> 503;
                            default -> 204;
                        } : 400;
                exchange.sendResponseHeaders(status, -1);
            }
        });
        upstream.createContext("/internal/projects/", exchange -> {
            try (exchange) {
                PROJECT_CALLS.incrementAndGet();
                PROJECT_BEARER.set(exchange.getRequestHeaders().getFirst("Authorization"));
                int status = "GET".equals(exchange.getRequestMethod())
                        && "evidenceScope=PUBLIC_ONLY".equals(exchange.getRequestURI().getQuery())
                        && exchange.getRequestURI().getPath().endsWith("/interview-data")
                        && "interview".equals(exchange.getRequestHeaders().getFirst("X-DevField-Service"))
                        && X01_KEY.equals(exchange.getRequestHeaders().getFirst("X-DevField-Service-Key"))
                        && PROJECT_BEARER.get() != null ? PROJECT_STATUS.get() : 400;
                if (status == 200) {
                    byte[] body = JSON.writeValueAsBytes(SNAPSHOT.get());
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                } else exchange.sendResponseHeaders(status, -1);
            }
        });
        upstream.createContext("/api/chat", exchange -> {
            try (exchange) {
                OLLAMA_REQUEST.set(JSON.readTree(exchange.getRequestBody()));
                int status = OLLAMA_STATUS.get();
                if (status != 200) {
                    exchange.sendResponseHeaders(status, -1);
                    return;
                }
                byte[] body = OllamaFirstQuestionGeneratorTest.envelope(
                        OllamaFirstQuestionGeneratorTest.content(OLLAMA_RESPONSE_EVIDENCE.get()), true, "stop");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        upstream.start();
        String base = "http://127.0.0.1:" + upstream.getAddress().getPort();
        registry.add("devfield.interview.auth.session-check-uri", () -> base + "/internal/auth/session-check");
        registry.add("devfield.interview.auth.service-key", () -> AUTH_KEY);
        registry.add("devfield.interview.project.base-url", () -> base);
        registry.add("devfield.interview.project.service-key", () -> X01_KEY);
    }

    @AfterAll static void stopUpstream() { upstream.stop(0); }

    @BeforeEach void setup() {
        PROJECT_CALLS.set(0);
        OLLAMA_STATUS.set(200);
        OLLAMA_REQUEST.set(null);
        PROJECT_STATUS.set(200);
        SNAPSHOT.set(snapshot(1, "a"));
        when(decoder.decode(anyString())).thenAnswer(invocation -> {
            String value = invocation.getArgument(0);
            String[] parts = value.split("\\.");
            if (parts.length != 3 || "invalid".equals(parts[0])) throw new BadJwtException("fixture invalid");
            return Jwt.withTokenValue(value).header("alg", "RS256").subject(parts[1])
                    .issuedAt(Instant.now().minusSeconds(1)).expiresAt(Instant.now().plusSeconds(120)).build();
        });
    }

    @AfterEach void clearSessions() {
        db.update("DELETE FROM interview.interview_session WHERE owner_user_id=?", owner);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ollamaHttpRetryKeepsPinnedEvidenceAndWorkerRejectsMismatchedOutput(boolean mismatched) throws Exception {
        var pinned = (ProjectSnapshot) SNAPSHOT.get();
        UUID evidence = UUID.fromString(pinned.topics().getFirst().primaryEvidenceId());
        UUID id = startForGeneration("JAVA");
        var ollama = new OllamaFirstQuestionGenerator(upstream.getAddress().getPort(), Duration.ofSeconds(3));
        var ollamaWorker = new FirstQuestionWorker(db, tx, sessions, ollama);

        OLLAMA_STATUS.set(503);
        ollamaWorker.poll();
        assertEquals("QUEUED", jobState(id));
        var firstRequest = OLLAMA_REQUEST.get();
        assertEquals(evidence.toString(), firstRequest.at("/format/properties/evidenceId/enum/0").asText());
        SNAPSHOT.set(snapshot(2, "b"));
        OLLAMA_STATUS.set(200);
        OLLAMA_RESPONSE_EVIDENCE.set(mismatched ? UUID.randomUUID() : evidence);
        makeDue(id);
        ollamaWorker.poll();

        assertEquals(firstRequest, OLLAMA_REQUEST.get());
        var read = detail(id);
        assertEquals(pinned.snapshotId(), read.path("snapshotId").asText());
        assertEquals(mismatched ? "GENERATION_FAILED" : "ACTIVE", read.path("status").asText());
        assertEquals(!mismatched, read.has("currentQuestion"));
        assertEquals(1, PROJECT_CALLS.get());
    }

    @Test void firstQuestionRetriesWithPinnedEvidenceAndPublishesExactlyOnce() throws Exception {
        var pinned = (ProjectSnapshot) SNAPSHOT.get();
        UUID id = startForGeneration("RANDOM");
        var firstInput = new AtomicReference<FirstQuestionGenerator.Input>();
        doAnswer(call -> {
            FirstQuestionGenerator.Input input = call.getArgument(0);
            if (firstInput.compareAndSet(null, input)) throw new FirstQuestionGenerator.Retryable();
            assertEquals(firstInput.get(), input);
            return call.callRealMethod();
        }).when(generator).generate(any());

        worker.poll();
        assertEquals("QUEUED", jobState(id));
        assertEquals("PREPARING", detail(id).path("status").asText());
        assertEquals(UUID.fromString(pinned.topics().getFirst().primaryEvidenceId()), firstInput.get().evidenceId());
        assertEquals(pinned.evidenceReferences().getFirst().summary(), firstInput.get().evidenceSummary());
        SNAPSHOT.set(snapshot(2, "b"));
        makeDue(id);
        new FirstQuestionWorker(db, tx, sessions, generator).poll();
        var ready = detail(id);
        assertEquals("ACTIVE", ready.path("status").asText());
        assertEquals(pinned.snapshotId(), ready.path("snapshotId").asText());
        assertEquals("RANDOM", ready.path("topicKey").asText());
        assertEquals("JAVA", ready.path("currentQuestion").path("topicKey").asText());
        assertTrue(ready.path("currentQuestion").path("text").asText().contains(pinned.evidenceReferences().getFirst().summary()));
        assertFalse(ready.path("currentQuestion").has("evidenceId"));
        assertEquals(404, get(bearer("fixture", UUID.randomUUID()), id).statusCode());
        worker.poll();
        assertEquals(ready, detail(id));
        verify(generator, times(2)).generate(any());
        assertEquals(1, PROJECT_CALLS.get());
    }

    @ParameterizedTest @ValueSource(strings = {"invalid-json", "unbound-evidence", "invalid-pinned-evidence", "retry-exhausted"})
    void generationFailsClosed(String scenario) throws Exception {
        UUID id = startForGeneration("JAVA");
        if ("invalid-pinned-evidence".equals(scenario)) {
            db.update("UPDATE interview.first_question_job SET evidence_id=? WHERE session_id=?", UUID.randomUUID(), id);
        } else if ("retry-exhausted".equals(scenario)) {
            doThrow(new FirstQuestionGenerator.Retryable()).when(generator).generate(any());
        } else {
            doReturn("invalid-json".equals(scenario) ? "{" :
                    "{\"question\":\"What changed?\",\"evidenceId\":\"" + UUID.randomUUID() + "\"}")
                    .when(generator).generate(any());
        }

        worker.poll();
        if ("retry-exhausted".equals(scenario)) {
            assertEquals("QUEUED", jobState(id));
            makeDue(id);
            worker.poll();
            assertEquals("QUEUED", jobState(id));
            makeDue(id);
            worker.poll();
            verify(generator, times(3)).generate(any());
        }
        if ("invalid-pinned-evidence".equals(scenario)) verifyNoInteractions(generator);
        assertEquals("FAILED", jobState(id));
        var failed = detail(id);
        assertEquals("GENERATION_FAILED", failed.path("status").asText());
        assertFalse(failed.has("currentQuestion"));
    }

    @Test void expiredAttemptCannotPublishWhileTheNextAttemptIsRunning() throws Exception {
        UUID id = startForGeneration("JAVA");
        var firstEntered = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        var calls = new AtomicInteger();
        doAnswer(call -> {
            FirstQuestionGenerator.Input input = call.getArgument(0);
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                assertTrue(releaseFirst.await(15, TimeUnit.SECONDS));
                return "{\"question\":\"Old result\",\"evidenceId\":\"" + input.evidenceId() + "\"}";
            }
            secondEntered.countDown();
            assertTrue(releaseSecond.await(15, TimeUnit.SECONDS));
            return call.callRealMethod();
        }).when(generator).generate(any());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(worker::poll);
            try {
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
                worker.poll();
                assertEquals(1, calls.get());
                db.update("UPDATE interview.first_question_job SET lease_until=clock_timestamp(),due_at=clock_timestamp() WHERE session_id=?", id);
                var second = executor.submit(new FirstQuestionWorker(db, tx, sessions, generator)::poll);
                assertTrue(secondEntered.await(5, TimeUnit.SECONDS));

                releaseFirst.countDown();
                first.get(5, TimeUnit.SECONDS);
                assertTrue(db.queryForObject("""
                        SELECT state='RUNNING' AND attempt=2 AND lease_until>clock_timestamp()
                            AND question_id IS NULL AND question_text IS NULL
                        FROM interview.first_question_job WHERE session_id=?
                        """, Boolean.class, id));
                var preparing = detail(id);
                assertEquals("PREPARING", preparing.path("status").asText());
                assertFalse(preparing.has("currentQuestion"));

                releaseSecond.countDown();
                second.get(5, TimeUnit.SECONDS);
            } finally {
                releaseFirst.countDown();
                releaseSecond.countDown();
            }
        }
        assertEquals(2, calls.get());
        assertEquals("READY", jobState(id));
        var ready = detail(id);
        assertEquals("ACTIVE", ready.path("status").asText());
        assertFalse(ready.path("currentQuestion").path("text").asText().isBlank());
        assertNotEquals("Old result", ready.path("currentQuestion").path("text").asText());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void deletionRejectsInFlightQuestionAndCascadesPublishedQuestion(boolean userDeletion) throws Exception {
        UUID published = startForGeneration("JAVA");
        worker.poll();
        assertEquals("READY", jobState(published));
        UUID late = startForGeneration("JAVA");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return call.callRealMethod();
        }).when(generator).generate(any());

        String blocker = "interview.test_question_" + published.toString().replace("-", "");
        UUID deletionJob = UUID.randomUUID();
        db.execute("CREATE TABLE " + blocker + " (id uuid REFERENCES interview.interview_session(id))");
        try (var executor = Executors.newSingleThreadExecutor()) {
            db.update("INSERT INTO " + blocker + " VALUES (?)", published);
            var running = executor.submit(worker::poll);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(503, delete(userDeletion, deletionJob).statusCode());
            } finally { release.countDown(); }
            running.get(10, TimeUnit.SECONDS);
            assertEquals("RUNNING", jobState(late));
            assertNull(db.queryForObject("SELECT question_id FROM interview.first_question_job WHERE session_id=?", UUID.class, late));
            assertEquals(404, get(bearer("fixture", owner), late).statusCode());
        } finally {
            db.execute("DROP TABLE " + blocker);
        }
        assertEquals(204, delete(userDeletion, deletionJob).statusCode());
        assertEquals(404, get(bearer("fixture", owner), published).statusCode());
        assertEquals(404, get(bearer("fixture", owner), late).statusCode());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM interview.first_question_job WHERE session_id IN (?,?)",
                Integer.class, published, late));
    }

    private UUID startForGeneration(String topic) throws Exception {
        var created = start(bearer("fixture", owner), "FREE", topic);
        assertEquals(201, created.statusCode(), created.body());
        var body = JSON.readTree(created.body());
        assertEquals("PREPARING", body.path("status").asText());
        assertFalse(body.has("currentQuestion"));
        UUID id = UUID.fromString(body.path("interviewId").asText());
        assertEquals("QUEUED", jobState(id));
        return id;
    }

    private JsonNode detail(UUID id) throws Exception {
        var response = get(bearer("fixture", owner), id);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private void makeDue(UUID id) {
        db.update("UPDATE interview.first_question_job SET due_at=clock_timestamp() WHERE session_id=?", id);
    }

    private String jobState(UUID id) {
        return db.queryForObject("SELECT state FROM interview.first_question_job WHERE session_id=?", String.class, id);
    }

    @Test void createsIndependentSessionsAndReadsOnlyThePinnedOwnerMetadata() throws Exception {
        var original = (ProjectSnapshot) SNAPSHOT.get();
        String ownerBearer = bearer("fixture", owner);
        var first = start(ownerBearer, "FREE", "JAVA");
        assertEquals(201, first.statusCode(), first.body());
        var response = JSON.readTree(first.body());
        UUID id = UUID.fromString(response.path("interviewId").asText());
        assertEquals("/api/interviews/" + id, first.headers().firstValue("Location").orElseThrow());
        assertEquals("no-store", first.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("PREPARING", response.path("status").asText());
        assertEquals(original.snapshotId(), response.path("snapshotId").asText());
        assertEquals("JAVA", response.path("topicKey").asText());
        assertEquals(0, response.path("completedBundles").asInt(-1));
        assertEquals(6, response.size());
        assertEquals(ownerBearer, PROJECT_BEARER.get());

        SNAPSHOT.set(snapshot(2, "b"));
        var second = start(ownerBearer, "FREE", "RANDOM");
        var third = start(ownerBearer, "FREE", "RANDOM");
        assertEquals(201, second.statusCode());
        assertEquals(201, third.statusCode());
        assertNotEquals(JSON.readTree(second.body()).path("interviewId"), JSON.readTree(third.body()).path("interviewId"));
        assertEquals("RANDOM", JSON.readTree(second.body()).path("topicKey").asText());
        var read = get(ownerBearer, id);
        assertEquals(200, read.statusCode());
        assertEquals(response, JSON.readTree(read.body()));
        assertEquals(404, get(bearer("fixture", UUID.randomUUID()), id).statusCode());
        assertEquals(404, get(ownerBearer, UUID.randomUUID()).statusCode());
        assertEquals(401, get(bearer("expired", owner), id).statusCode());
        assertEquals(403, get(bearer("deleting", owner), id).statusCode());
        assertEquals(503, get(bearer("outage", owner), id).statusCode());
        assertEquals(3, PROJECT_CALLS.get());
        assertEquals(original, sessions.find(owner, id).orElseThrow().snapshot());
        assertEquals(1L, db.queryForObject("SELECT membership_version FROM interview.interview_session WHERE id=?", Long.class, id));
    }

    @Test void answerRouteIsDisabledByDefaultEvenWithSessionsEnabled() throws Exception {
        var request = request("/api/interviews/" + UUID.randomUUID() + "/answers", bearer("fixture", owner))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(403, http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    @Test void lowercaseBearerUsesCanonicalSchemeForBothEndpoints() throws Exception {
        String canonical = bearer("fixture", owner);
        String lowercase = "bearer " + canonical.substring(7);
        var created = start(lowercase, "FREE", "JAVA");
        assertEquals(201, created.statusCode(), created.body());
        assertEquals(canonical, PROJECT_BEARER.get());
        UUID id = UUID.fromString(JSON.readTree(created.body()).path("interviewId").asText());

        var read = get(lowercase, id);
        assertEquals(200, read.statusCode(), read.body());
        assertEquals(id.toString(), JSON.readTree(read.body()).path("interviewId").asText());
        assertEquals(1, PROJECT_CALLS.get());
    }

    @Test void rejectsIneligibleStartsWithoutWritingASession() throws Exception {
        String token = bearer("fixture", owner);
        assertEquals(401, start(null, "FREE", "JAVA").statusCode());
        assertEquals(401, start(bearer("invalid", owner), "FREE", "JAVA").statusCode());
        assertEquals(401, start(bearer("expired", owner), "FREE", "JAVA").statusCode());
        assertEquals(403, start(bearer("deleting", owner), "FREE", "JAVA").statusCode());
        assertEquals(503, start(bearer("outage", owner), "FREE", "JAVA").statusCode());
        assertEquals(400, start(token, "MOCK", null).statusCode());
        assertEquals(400, start(token, "FREE", null).statusCode());
        assertEquals(0, PROJECT_CALLS.get());
        assertEquals(400, start(token, "FREE", "INACTIVE").statusCode());
        for (int upstreamStatus : List.of(403, 404, 409, 503)) {
            PROJECT_STATUS.set(upstreamStatus);
            assertEquals(upstreamStatus, start(token, "FREE", "JAVA").statusCode());
        }
        PROJECT_STATUS.set(200);
        SNAPSHOT.set(Map.of("snapshotId", "invalid"));
        assertEquals(503, start(token, "FREE", "JAVA").statusCode());
        assertEquals(0, count());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void barrierCommittedAfterX01RejectsLateCreation(boolean userDeletion) throws Exception {
        var fetched = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object snapshot = invocation.callRealMethod();
            fetched.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return snapshot;
        }).when(snapshots).fetch(eq(project), anyString());

        try (var thread = Executors.newSingleThreadExecutor()) {
            var request = thread.submit(() -> start(bearer("fixture", owner), "FREE", "JAVA"));
            try {
                assertTrue(fetched.await(5, TimeUnit.SECONDS));
                assertEquals(204, delete(userDeletion).statusCode());
            } finally { release.countDown(); }
            assertEquals(409, request.get(10, TimeUnit.SECONDS).statusCode());
        }
        assertEquals(0, count());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void creationCommittedBeforeBarrierIsIncludedInCleanup(boolean userDeletion) throws Exception {
        var committed = new CompletableFuture<UUID>();
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            var session = (InterviewSessions.Session) invocation.callRealMethod();
            committed.complete(session.id());
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return session;
        }).when(sessions).create(eq(owner), eq(project), eq(InterviewSessions.Mode.FREE), any(), eq("JAVA"));

        String token = bearer("fixture", owner);
        try (var thread = Executors.newSingleThreadExecutor()) {
            var request = thread.submit(() -> start(token, "FREE", "JAVA"));
            try {
                UUID id = committed.get(5, TimeUnit.SECONDS);
                assertEquals(1, count());
                assertEquals(204, delete(userDeletion).statusCode());
                assertEquals(404, get(token, id).statusCode());
            } finally { release.countDown(); }
            assertEquals(201, request.get(10, TimeUnit.SECONDS).statusCode());
        }
        assertEquals(0, count());
    }

    private int count() {
        return db.queryForObject("SELECT count(*) FROM interview.interview_session WHERE owner_user_id=?", Integer.class, owner);
    }

    private HttpResponse<String> start(String bearer, String mode, String topic) throws Exception {
        var body = JSON.createObjectNode().put("projectId", project.toString()).put("mode", mode);
        if (topic != null) body.put("topicKey", topic);
        var request = request("/api/interviews", bearer).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String bearer, UUID id) throws Exception {
        return http.send(request("/api/interviews/" + id, bearer).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(boolean userDeletion) throws Exception {
        return delete(userDeletion, UUID.randomUUID());
    }

    private HttpResponse<String> delete(boolean userDeletion, UUID job) throws Exception {
        Map<String, ?> command = userDeletion
                ? Map.of("userId", owner, "deletionJobId", job, "state", "DELETING")
                : Map.of("ownerUserId", owner, "projectId", project, "deletionJobId", job);
        var request = request(userDeletion ? "/internal/users/" + owner + "/data"
                : "/internal/projects/" + project + "/interviews", null)
                .header("Content-Type", "application/json")
                .header("X-DevField-Service", userDeletion ? "auth" : "project")
                .header("X-DevField-Service-Key", userDeletion ? "x04-auth-test-credential-only-012345678"
                        : "x02-project-test-credential-only-012345")
                .method("DELETE", HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(command)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path, String bearer) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (bearer != null) request.header("Authorization", bearer);
        return request;
    }

    private static String bearer(String kind, UUID user) { return "Bearer " + kind + "." + user + ".signature"; }

    private static ProjectSnapshot snapshot(long version, String hash) {
        String member = UUID.randomUUID().toString(), repository = UUID.randomUUID().toString();
        String evidence = UUID.randomUUID().toString(), sha = hash.repeat(40);
        return new ProjectSnapshot(UUID.randomUUID().toString(), version,
                List.of(new ProjectSnapshot.Member(member, repository, "42", sha)),
                List.of(new ProjectSnapshot.Topic("JAVA", evidence)), List.of(),
                List.of(new ProjectSnapshot.Evidence(evidence, member, repository, "42", sha,
                        "src/Main.java", 1, 1, "CODE_FACT", "Declares a Java class.")));
    }
}
