package dev.devfield.repoq.interview.project;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import dev.devfield.repoq.interview.security.InterviewAuthentication;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;

class ProjectSnapshotClientTest {
    private static final String KEY = "interview-x01-key-for-loopback-tests-only";
    private static final String BEARER = "Bearer fixture.jwt.signature";
    private static final UUID PROJECT = UUID.randomUUID();
    private static final String MEMBER = UUID.randomUUID().toString();
    private static final String REPOSITORY = UUID.randomUUID().toString();
    private static final String EVIDENCE = UUID.randomUUID().toString();
    private static final String SHA = "0".repeat(40);
    private static final String VALID = """
            {"snapshotId":"%s","membershipVersion":1,
             "members":[{"memberId":"%s","repositoryId":"%s","githubRepositoryId":"42","commitSha":"%s"}],
             "topics":[{"key":"API_BACKEND","primaryEvidenceId":"%s"}],"relations":[],
             "evidenceReferences":[{"id":"%s","memberId":"%s","repositoryId":"%s","githubRepositoryId":"42",
             "commitSha":"%s","filePath":"src/App.java","lineStart":1,"lineEnd":2,"claimType":"CODE_FACT",
             "summary":"Declares a REST controller."}]}
            """.formatted(UUID.randomUUID(), MEMBER, REPOSITORY, SHA, EVIDENCE, EVIDENCE, MEMBER, REPOSITORY, SHA);
    private HttpServer server;
    private InterviewAuthentication auth;
    private final AtomicBoolean authenticatedRequest = new AtomicBoolean();
    private volatile String body = VALID;
    private volatile int status = 200;
    private volatile boolean stall;

    @BeforeEach void start() throws Exception {
        auth = mock(InterviewAuthentication.class);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/projects/", exchange -> {
            try (exchange) {
                authenticatedRequest.set(exchange.getRequestMethod().equals("GET") &&
                        exchange.getRequestURI().toString().equals("/internal/projects/" + PROJECT +
                                "/interview-data?evidenceScope=PUBLIC_ONLY") &&
                        BEARER.equals(exchange.getRequestHeaders().getFirst("Authorization")) &&
                        "interview".equals(exchange.getRequestHeaders().getFirst("X-DevField-Service")) &&
                        KEY.equals(exchange.getRequestHeaders().getFirst("X-DevField-Service-Key")));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                if (stall) {
                    try { Thread.sleep(1500); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private ProjectSnapshotClient client(Duration timeout) {
        return new ProjectSnapshotClient(auth, "http://127.0.0.1:" + server.getAddress().getPort(), KEY, timeout);
    }

    @Test void verifiesSessionAndSendsBothCredentials() {
        var result = client(Duration.ofSeconds(3)).fetch(PROJECT, BEARER);
        assertTrue(authenticatedRequest.get());
        verify(auth).verify(BEARER);
        assertEquals(EVIDENCE, result.topics().getFirst().primaryEvidenceId());
        assertEquals(SHA, result.evidenceReferences().getFirst().commitSha());
    }

    @Test void rejectsMalformedAndUnboundEvidence() {
        for (String invalid : new String[] {
                "null", VALID + "{}", VALID.replace(SHA, "not-a-sha"), VALID.replace(MEMBER, "not-a-uuid"),
                VALID.replace("\"memberId\":\"" + MEMBER + "\",\"repositoryId\":\"" + REPOSITORY + "\",\"githubRepositoryId\":\"42\",\n",
                        "\"memberId\":\"" + UUID.randomUUID() + "\",\"repositoryId\":\"" + REPOSITORY + "\",\"githubRepositoryId\":\"42\",\n"),
                VALID.replace("\"primaryEvidenceId\":\"" + EVIDENCE + "\"", "\"primaryEvidenceId\":\"" + UUID.randomUUID() + "\""),
                VALID.replace("CODE_FACT", "DOC_CLAIM"), VALID.replace("\"lineEnd\":2", "\"lineEnd\":null"),
                VALID.replace("\"membershipVersion\":1", "\"membershipVersion\":\"1\""),
                VALID.replace("\"githubRepositoryId\":\"42\"", "\"githubRepositoryId\":42"),
                VALID.replace(",\"lineEnd\":2", ""), " ".repeat(ProjectSnapshotClient.MAX_BYTES + 1) }) {
            body = invalid;
            assertRejected(ProjectSnapshotClient.Failure.INVALID_RESPONSE, client(Duration.ofSeconds(3)));
        }
    }

    @Test void ignoresUpstreamErrorBodyAndTimesOutAfterHeaders() {
        body = "{\"code\":\"unsafe-upstream-payload\"}";
        status = 404;
        assertRejected(ProjectSnapshotClient.Failure.NOT_FOUND, client(Duration.ofSeconds(3)));
        status = 409;
        assertRejected(ProjectSnapshotClient.Failure.SNAPSHOT_UNAVAILABLE, client(Duration.ofSeconds(3)));
        status = 503;
        assertRejected(ProjectSnapshotClient.Failure.UPSTREAM_UNAVAILABLE, client(Duration.ofSeconds(3)));
        status = 200;
        stall = true;
        assertRejected(ProjectSnapshotClient.Failure.UPSTREAM_UNAVAILABLE, client(Duration.ofMillis(250)));
    }

    private void assertRejected(ProjectSnapshotClient.Failure expected, ProjectSnapshotClient client) {
        var error = assertThrows(ProjectSnapshotClient.Rejected.class, () -> client.fetch(PROJECT, BEARER));
        assertEquals(expected, error.failure());
        assertEquals(expected.name(), error.getMessage());
        assertNull(error.getCause());
    }
}
