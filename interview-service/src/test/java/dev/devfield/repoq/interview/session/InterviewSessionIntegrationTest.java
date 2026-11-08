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
    private static HttpServer upstream;

    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
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
        UUID job = UUID.randomUUID();
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
