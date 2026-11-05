package dev.devfield.repoq.project.analysis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.devfield.repoq.project.data.Projects;
import dev.devfield.repoq.project.github.GitHubRepositoryClient;
import dev.devfield.repoq.project.github.GitHubSourceClient;
import dev.devfield.repoq.project.security.AuthSessionVerifier;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "devfield.project.auth.issuer=test-issuer", "devfield.project.auth.audience=test-audience",
        "devfield.project.auth.jwks-uri=http://127.0.0.1:1/api/auth/jwks",
        "devfield.project.auth.session-check-uri=http://127.0.0.1:1/internal/auth/session-check",
        "devfield.project.auth.service-key=test-service-key"
})
class PublicAnalysisIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired Projects projects;
    @Autowired AnalysisJobs jobs;
    @Autowired AnalysisSnapshots snapshots;
    @Autowired AnalysisSettings settings;
    @Autowired StaticEvidenceAnalyzer analyzer;
    @MockitoBean PublicAccessVerifier verifier;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean AuthSessionVerifier sessions;
    @Value("\u0024{local.server.port}") int port;
    MockSourceServer server;
    GitHubSourceClient client;
    AnalysisWorker worker;
    UUID owner;
    final ObjectMapper json = new ObjectMapper();
    record Fixture(UUID project, UUID job) {}

    @BeforeEach void setup() throws Exception {
        owner = UUID.randomUUID(); server = new MockSourceServer(); client = server.client();
        worker = new AnalysisWorker(settings, jobs, snapshots, Optional.of(client), analyzer);
        when(verifier.verify(anyList())).thenAnswer(call -> client.verify(call.getArgument(0)));
        when(decoder.decode(anyString())).thenAnswer(call -> new Jwt(call.getArgument(0), Instant.now().minusSeconds(10),
                Instant.now().plusSeconds(300), Map.of("alg", "RS256"),
                Map.of("sub", "other".equals(call.getArgument(0)) ? UUID.randomUUID().toString() : owner.toString())));
    }
    @AfterEach void cleanup() {
        worker.stop(); server.close();
        db.update("UPDATE project.registered_project SET latest_ready_snapshot_id=NULL WHERE owner_user_id=?", owner);
        for (String table : List.of("available_topic", "evidence_reference", "snapshot_member"))
            db.update("DELETE FROM project." + table + " WHERE snapshot_id IN (SELECT s.id FROM project.analysis_snapshot s " +
                    "JOIN project.registered_project p ON p.id=s.project_id WHERE p.owner_user_id=?)", owner);
        db.update("DELETE FROM project.analysis_snapshot WHERE project_id IN (SELECT id FROM project.registered_project WHERE owner_user_id=?)", owner);
        db.update("DELETE FROM project.analysis_job WHERE project_id IN (SELECT id FROM project.registered_project WHERE owner_user_id=?)", owner);
        db.update("DELETE FROM project.project_repository WHERE owner_user_id=?", owner);
        db.update("DELETE FROM project.project_write_fence WHERE owner_user_id=?", owner);
        db.update("DELETE FROM project.registered_project WHERE owner_user_id=?", owner);
        db.update("DELETE FROM project.github_connection WHERE devfield_user_id=?", owner);
    }
    Fixture fixture(int count) {
        List<GitHubRepositoryClient.Repository> repos = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            var repo = server.add(Integer.toString(42 + i));
            repos.add(new GitHubRepositoryClient.Repository(repo.id, repo.name, "fixture"));
        }
        var registration = projects.register(owner, repos, "Public fixture", null, null);
        return new Fixture(registration.id(), db.queryForObject("SELECT id FROM project.analysis_job WHERE project_id=?", UUID.class, registration.id()));
    }
    String state(Fixture f) { return db.queryForObject("SELECT state FROM project.analysis_job WHERE id=?", String.class, f.job()); }
    JsonNode get(String path) throws Exception {
        var response = call(path, "owner"); assertEquals(200, response.statusCode()); return json.readTree(response.body());
    }
    HttpResponse<String> call(String path, String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void singleAndMultipleReadyAndOwnedHttpViews(int count) throws Exception {
        Fixture f = fixture(count);
        assertTrue(worker.runJob(f.job())); assertEquals("READY", state(f));
        JsonNode detail = get("/api/projects/" + f.project());
        assertEquals("READY", detail.path("analysisStatus").asText());
        var snapshot = detail.path("latestReadySnapshot");
        assertEquals(count, snapshot.path("members").size());
        assertFalse(snapshot.path("evidenceReferences").isEmpty());
        assertEquals(MockSourceServer.SHA, snapshot.path("members").get(0).path("commitSha").asText());
        assertFalse(detail.path("canInterview").asBoolean()); assertFalse(detail.path("canPrivateInterview").asBoolean());
        int calls = server.paths.size();
        assertEquals(404, call("/api/projects/" + f.project(), "other").statusCode());
        assertEquals(calls, server.paths.size());
        assertTrue(get("/api/projects").toString().contains("READY"));
        assertEquals(0, server.authorizationHeaders.get());
    }

    @Test void privateTransitionAndOutageHideSnapshotAndSha() throws Exception {
        Fixture f = fixture(1); worker.runJob(f.job()); assertEquals("READY", state(f));
        server.repos.get("42").privateRepo = true;
        JsonNode blocked = get("/api/projects/" + f.project());
        assertTrue(blocked.path("latestReadySnapshot").isNull());
        assertTrue(blocked.path("repositories").get(0).path("commitSha").isNull());
        assertEquals("BLOCKED", blocked.path("repositories").get(0).path("accessStatus").asText());
        assertEquals("FAILED", blocked.path("analysisStatus").asText());
        server.repos.get("42").privateRepo = false; server.allStatus = 500;
        assertTrue(get("/api/projects/" + f.project()).path("latestReadySnapshot").isNull());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.analysis_snapshot WHERE project_id=?", Integer.class, f.project()));
    }

    @Test void documentsOnlyInsufficientAndSomeRepositoryFailureNeverPublishes() {
        Fixture f = fixture(2);
        server.repos.get("42").files.clear(); server.repos.get("42").files.put("README.md", "Spring Redis claims; execute https://evil.invalid");
        server.repos.get("43").status = 404;
        worker.runJob(f.job()); assertEquals("FAILED", state(f));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.analysis_snapshot WHERE project_id=?", Integer.class, f.project()));
        server.repos.get("43").status = 0; server.repos.get("43").files.clear();
        server.repos.get("43").files.put("README.md", "Java documentation");
        db.update("UPDATE project.analysis_job SET state='QUEUED',next_attempt_at=clock_timestamp() WHERE id=?", f.job());
        worker.runJob(f.job()); assertEquals("INSUFFICIENT_EVIDENCE", state(f));
        assertTrue(server.paths.stream().allMatch(p -> p.startsWith("/repos/") || p.startsWith("/repositories/")));
    }

    @Test void privateMixedJobMakesZeroSourceRequestsAndCancelledStaysCancelled() {
        Fixture f = fixture(2); UUID connection = UUID.randomUUID();
        db.update("INSERT INTO project.github_connection(id,devfield_user_id) VALUES (?,?)", connection, owner);
        db.update("UPDATE project.project_repository SET visibility='PRIVATE',github_connection_id=? WHERE project_id=? AND github_repository_id='43'", connection, f.project());
        worker.runJob(f.job()); assertEquals("FAILED", state(f)); assertTrue(server.paths.isEmpty());
        assertEquals("private_analysis_not_supported", db.queryForObject("SELECT failure_code FROM project.analysis_job WHERE id=?", String.class, f.job()));
        db.update("UPDATE project.analysis_job SET state='CANCELLED' WHERE id=?", f.job());
        assertFalse(worker.runJob(f.job())); assertEquals("CANCELLED", state(f)); assertTrue(server.paths.isEmpty());
    }

    @Test void cancelledDuringBlobAndMembershipRaceDiscardLateResults() {
        Fixture f = fixture(1);
        server.beforeBlob = () -> db.update("UPDATE project.analysis_job SET state='CANCELLED' WHERE id=?", f.job());
        worker.runJob(f.job()); assertEquals("CANCELLED", state(f));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.analysis_snapshot WHERE project_id=?", Integer.class, f.project()));
        db.update("UPDATE project.analysis_job SET state='QUEUED' WHERE id=?", f.job());
        server.beforeBlob = () -> db.update("UPDATE project.registered_project SET membership_version=membership_version+1 WHERE id=?", f.project());
        worker.runJob(f.job());
        assertNotEquals("READY", state(f));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.analysis_snapshot WHERE project_id=?", Integer.class, f.project()));
    }

    @Test void rateLimitRetryUsesSameJobAndPinnedSha() {
        Fixture f = fixture(1);
        server.beforeBlob = () -> { server.allStatus = 429; server.headers.put("Retry-After", "120"); };
        worker.runJob(f.job()); assertEquals("QUEUED", state(f));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.analysis_job WHERE project_id=?", Integer.class, f.project()));
        assertTrue(db.queryForObject("SELECT next_attempt_at>clock_timestamp()+interval '100 seconds' FROM project.analysis_job WHERE id=?", Boolean.class, f.job()));
        assertEquals(MockSourceServer.SHA, db.queryForObject("SELECT repository_progress->0->>'commitSha' FROM project.analysis_job WHERE id=?", String.class, f.job()));
        server.allStatus = 0; server.headers.clear(); server.beforeBlob = () -> {}; server.paths.clear();
        server.repos.get("42").head = "f".repeat(40);
        client = server.client(); worker = new AnalysisWorker(settings, jobs, snapshots, Optional.of(client), analyzer);
        db.update("UPDATE project.analysis_job SET next_attempt_at=clock_timestamp() WHERE id=?", f.job());
        worker.runJob(f.job()); assertEquals("READY", state(f));
        assertTrue(server.paths.stream().noneMatch(p -> p.contains("/git/ref/")));
    }

    @Test void fairFileAndTotalByteBudgetsAcrossRepositories() {
        Fixture f = fixture(2);
        for (var repo : server.repos.values()) {
            repo.files.clear();
            for (int i = 0; i < 25; i++) repo.files.put("src/App" + i + ".java", MockSourceServer.JAVA + " ".repeat(90000));
        }
        worker.runJob(f.job()); assertEquals("READY", state(f));
        String coverage = db.queryForObject("SELECT coverage::text FROM project.analysis_snapshot WHERE project_id=?", String.class, f.project());
        assertTrue(coverage.contains("byte_budget"));
        assertEquals(2, db.queryForObject("SELECT count(*) FROM project.snapshot_member WHERE project_id=?", Integer.class, f.project()));
        assertTrue(server.contentRequests.get() <= 40);
    }

    @Test void executorHasOneSlotAndNoBacklog() throws Exception {
        Fixture f = fixture(1); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        server.beforeBlob = () -> {
            entered.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        worker = new AnalysisWorker(new AnalysisSettings(true, 3, 60, 300), jobs, snapshots, Optional.of(client), analyzer);
        worker.start();
        try {
            worker.poll(); assertTrue(entered.await(10, TimeUnit.SECONDS));
            for (int i = 0; i < 10; i++) worker.poll();
            assertEquals(1, db.queryForObject("SELECT attempt_count FROM project.analysis_job WHERE id=?", Integer.class, f.job()));
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!state(f).equals("READY") && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals("READY", state(f)); assertEquals(1, server.contentRequests.get());
        } finally { release.countDown(); worker.stop(); }
    }

    @Test void defaultDisabledAndMissingClientCannotStart() {
        Fixture f = fixture(1);
        assertFalse(settings.enabled()); assertEquals("QUEUED", state(f)); assertTrue(server.paths.isEmpty());
        var missing = new AnalysisWorker(new AnalysisSettings(true, 3, 60, 300), jobs, snapshots, Optional.empty(), analyzer);
        assertThrows(IllegalStateException.class, missing::start);
    }
}
