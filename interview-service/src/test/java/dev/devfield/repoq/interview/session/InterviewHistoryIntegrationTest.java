package dev.devfield.repoq.interview.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.devfield.repoq.interview.project.ProjectSnapshot;
import dev.devfield.repoq.interview.security.InterviewAuthentication;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "devfield.interview.sessions.enabled=true",
        "devfield.interview.history.enabled=true",
        "devfield.interview.session-deletion.enabled=true",
        "devfield.interview.deletion.project-key-sha256=4acb595e5ada8c9a973db7268d56e9ed27bc097157ce781b7ef0ce5ef401e95d",
        "devfield.interview.deletion.auth-key-sha256=9dc1622a4d732fd2b95d9733ff02dbdf1364f60465c4d10a8badcf779cab4c3e"
})
class InterviewHistoryIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-11-01T12:00:00.123456Z");

    @LocalServerPort int port;
    @Autowired InterviewSessions sessions;
    @Autowired JdbcTemplate db;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean InterviewAuthentication auth;

    private final UUID owner = UUID.randomUUID();
    private final UUID outsider = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID otherProject = UUID.randomUUID();
    private final UUID foreignProject = UUID.randomUUID();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void authenticate() {
        for (UUID user : List.of(owner, outsider)) {
            when(decoder.decode(token(user))).thenReturn(Jwt.withTokenValue(token(user)).header("alg", "RS256")
                    .subject(user.toString()).issuedAt(Instant.now().minusSeconds(1))
                    .expiresAt(Instant.now().plusSeconds(120)).build());
        }
    }

    @AfterEach
    void cleanup() {
        try (http) {
            db.update("DELETE FROM interview.interview_session WHERE owner_user_id IN (?,?)", owner, outsider);
            db.update("DELETE FROM interview.interview_write_fence WHERE owner_user_id IN (?,?)", owner, outsider);
        }
    }

    @Test
    void historyScopesByOwnerProjectAndFreeMode() throws Exception {
        UUID visible = seed(owner, project, InterviewSessions.Mode.FREE, CREATED);
        UUID other = seed(owner, otherProject, InterviewSessions.Mode.FREE, CREATED);
        UUID foreign = seed(outsider, foreignProject, InterviewSessions.Mode.FREE, CREATED);
        seed(owner, project, InterviewSessions.Mode.MOCK, CREATED);

        assertEquals(List.of(visible), ids(page(owner, project, "")));
        verify(auth).verify("Bearer " + token(owner));
        assertEquals(List.of(other), ids(page(owner, otherProject, "")));
        assertEquals(List.of(foreign), ids(page(outsider, foreignProject, "")));
        assertEmpty(page(owner, foreignProject, ""));
        assertEmpty(page(outsider, project, ""));
        assertEmpty(page(owner, UUID.randomUUID(), ""));
    }

    @Test
    void requiresAuthenticationAndValidProjectLimitAndCursor() throws Exception {
        assertEquals(401, http.send(request("/api/interviews?projectId=" + project).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(400, get(owner, "/api/interviews").statusCode());
        assertEquals(400, get(owner, "/api/interviews?projectId=invalid").statusCode());
        for (String query : List.of("&limit=0", "&limit=51", "&cursor=invalid", "&cursor=")) {
            assertEquals(400, history(owner, project, query).statusCode(), query);
        }

        seed(owner, project, InterviewSessions.Mode.FREE, CREATED);
        seed(owner, project, InterviewSessions.Mode.FREE, CREATED.plusSeconds(1));
        String cursor = page(owner, project, "&limit=1").path("nextCursor").asText();
        assertEquals(400, history(owner, otherProject, "&cursor=" + cursor).statusCode());
    }

    @ParameterizedTest @ValueSource(ints = {401, 403})
    void auth15RejectionHidesHistoryEvenWithAValidJwt(int status) throws Exception {
        seed(owner, project, InterviewSessions.Mode.FREE, CREATED);
        String bearer = "Bearer " + token(owner);
        String serviceKey = "history-auth-test-key";
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            upstream.createContext("/internal/auth/session-check", exchange -> {
                try (exchange) {
                    var headers = exchange.getRequestHeaders();
                    boolean valid = "POST".equals(exchange.getRequestMethod())
                            && bearer.equals(headers.getFirst("Authorization"))
                            && "interview".equals(headers.getFirst("X-DevField-Service"))
                            && serviceKey.equals(headers.getFirst("X-DevField-Service-Key"));
                    exchange.sendResponseHeaders(valid ? status : 500, -1);
                }
            });
            upstream.start();
            var verifier = new InterviewAuthentication(decoder,
                    "http://127.0.0.1:" + upstream.getAddress().getPort() + "/internal/auth/session-check", serviceKey);
            doAnswer(invocation -> {
                verifier.verify(invocation.getArgument(0));
                return null;
            }).when(auth).verify(bearer);

            var rejected = history(owner, project, "");
            assertEquals(status, rejected.statusCode(), rejected.body());
            assertEquals(JSON.valueToTree(Map.of("code", status == 401 ? "invalid_session" : "account_unavailable")),
                    JSON.readTree(rejected.body()));
            assertEquals("no-store", rejected.headers().firstValue("Cache-Control").orElseThrow());
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void cursorKeepsStableOrderWhenAnEqualTimestampBoundaryIsDeleted() throws Exception {
        UUID oldest = seed(owner, project, InterviewSessions.Mode.FREE, CREATED.minusSeconds(1));
        UUID tieA = seed(owner, project, InterviewSessions.Mode.FREE, CREATED);
        UUID tieB = seed(owner, project, InterviewSessions.Mode.FREE, CREATED);
        UUID newest = seed(owner, project, InterviewSessions.Mode.FREE, CREATED.plusSeconds(1));
        var tied = List.of(tieA, tieB).stream().sorted(Comparator.comparing(UUID::toString).reversed()).toList();
        db.update("UPDATE interview.interview_session SET state='ACTIVE',lifecycle='PAUSED' WHERE id=?", newest);
        db.update("UPDATE interview.interview_session SET state='ACTIVE',lifecycle='ENDED' WHERE id=?", oldest);
        db.update("UPDATE interview.interview_session SET state='GENERATION_FAILED' WHERE id=?", tied.getLast());
        addReadyEvaluation(newest);

        var first = page(owner, project, "&limit=2");
        assertEquals(List.of(newest, tied.getFirst()), ids(first));
        assertEquals("PAUSED", first.at("/items/0/status").asText());
        assertEquals("FREE", first.at("/items/0/mode").asText());
        assertEquals(1, first.at("/items/0/completedBundles").asInt());
        assertEquals(CREATED.plusSeconds(1).toInstant(),
                OffsetDateTime.parse(first.at("/items/0/createdAt").asText()).toInstant());
        assertEquals(5, first.at("/items/0").size());
        assertEquals("PREPARING", first.at("/items/1/status").asText());

        var deleted = http.send(request("/api/interviews/" + tied.getFirst())
                .header("Authorization", "Bearer " + token(owner)).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(204, deleted.statusCode());
        var next = page(owner, project, "&limit=2&cursor=" + first.path("nextCursor").asText());
        assertEquals(List.of(tied.getLast(), oldest), ids(next));
        assertEquals("GENERATION_FAILED", next.at("/items/0/status").asText());
        assertEquals("ENDED", next.at("/items/1/status").asText());
        assertTrue(next.path("nextCursor").isNull());
        assertEquals(List.of(newest, tied.getLast(), oldest), ids(page(owner, project, "")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void committedDeletionFenceHidesRowsEvenWithAnOlderCursor(boolean userDeletion) throws Exception {
        UUID blocked = seed(owner, project, InterviewSessions.Mode.FREE, CREATED);
        seed(owner, project, InterviewSessions.Mode.FREE, CREATED.plusSeconds(1));
        UUID sibling = seed(owner, otherProject, InterviewSessions.Mode.FREE, CREATED);
        UUID foreign = seed(outsider, foreignProject, InterviewSessions.Mode.FREE, CREATED);
        String cursor = page(owner, project, "&limit=1").path("nextCursor").asText();
        assertEquals(List.of(blocked), ids(page(owner, project, "&cursor=" + cursor)));

        UUID job = UUID.randomUUID();
        String blocker = "interview.test_history_" + blocked.toString().replace("-", "");
        db.execute("CREATE TABLE " + blocker + " (id uuid REFERENCES interview.interview_session(id))");
        try {
            db.update("INSERT INTO " + blocker + " VALUES (?)", blocked);
            assertEquals(503, deleteScope(userDeletion, job));
            assertEquals(2, db.queryForObject("""
                    SELECT count(*) FROM interview.interview_session WHERE owner_user_id=? AND project_id=?
                    """, Integer.class, owner, project));
            assertEmpty(page(owner, project, ""));
            assertEmpty(page(owner, project, "&cursor=" + cursor));
            assertEquals(userDeletion ? List.of() : List.of(sibling), ids(page(owner, otherProject, "")));
            assertEquals(List.of(foreign), ids(page(outsider, foreignProject, "")));
        } finally {
            db.execute("DROP TABLE " + blocker);
        }

        assertEquals(204, deleteScope(userDeletion, job));
        assertEmpty(page(owner, project, "&cursor=" + cursor));
    }

    private UUID seed(UUID user, UUID projectId, InterviewSessions.Mode mode, OffsetDateTime created) {
        var member = new ProjectSnapshot.Member(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "42", "a".repeat(40));
        var evidence = new ProjectSnapshot.Evidence(UUID.randomUUID().toString(), member.memberId(),
                member.repositoryId(), member.githubRepositoryId(), member.commitSha(), "src/Main.java", 1, 1,
                "CODE_FACT", "Declares a class.");
        var snapshot = new ProjectSnapshot(UUID.randomUUID().toString(), 1, List.of(member),
                List.of(new ProjectSnapshot.Topic("JAVA", evidence.id())), List.of(), List.of(evidence));
        UUID id = sessions.create(user, projectId, mode, snapshot).id();
        db.update("UPDATE interview.interview_session SET created_at=? WHERE id=?", created, id);
        return id;
    }

    private void addReadyEvaluation(UUID id) {
        UUID question = UUID.randomUUID();
        db.update("""
                INSERT INTO interview.question_turn(session_id,bundle_id,position,id,topic_key,evidence_id,question_text)
                VALUES (?,?,0,?,'JAVA',?,'Saved question')
                """, id, id, question, UUID.randomUUID());
        db.update("""
                INSERT INTO interview.user_answer(session_id,question_id,submission_id,answer_text,state)
                VALUES (?,?,'fixture','Saved answer','STOPPED')
                """, id, question);
        db.update("""
                INSERT INTO interview.bundle_evaluation(session_id,bundle_id,closing_question_id,state,result)
                VALUES (?,?,?,'READY','{}')
                """, id, id, question);
    }

    private JsonNode page(UUID user, UUID projectId, String query) throws Exception {
        var response = history(user, projectId, query);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        return JSON.readTree(response.body());
    }

    private static void assertEmpty(JsonNode page) {
        assertTrue(page.path("items").isArray());
        assertEquals(0, page.path("items").size());
        assertTrue(page.path("nextCursor").isNull());
    }

    private static List<UUID> ids(JsonNode page) {
        return StreamSupport.stream(page.path("items").spliterator(), false)
                .map(item -> UUID.fromString(item.path("interviewId").asText())).toList();
    }

    private HttpResponse<String> history(UUID user, UUID projectId, String query) throws Exception {
        return get(user, "/api/interviews?projectId=" + projectId + query);
    }

    private HttpResponse<String> get(UUID user, String path) throws Exception {
        return http.send(request(path).header("Authorization", "Bearer " + token(user)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private int deleteScope(boolean userDeletion, UUID job) throws Exception {
        String path = userDeletion ? "/internal/users/" + owner + "/data" : "/internal/projects/" + project + "/interviews";
        var command = userDeletion ? Map.of("userId", owner, "deletionJobId", job, "state", "DELETING")
                : Map.of("ownerUserId", owner, "projectId", project, "deletionJobId", job);
        return http.send(request(path).header("Content-Type", "application/json")
                .header("X-DevField-Service", userDeletion ? "auth" : "project")
                .header("X-DevField-Service-Key", userDeletion ? "x04-auth-test-credential-only-012345678"
                        : "x02-project-test-credential-only-012345")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(command))).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5));
    }

    private static String token(UUID user) { return "fixture." + user + ".signature"; }
}
