package dev.devfield.repoq.project.analysis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.devfield.repoq.project.deletion.InterviewDeletionClient;
import dev.devfield.repoq.project.deletion.ProjectDeletions;
import dev.devfield.repoq.project.api.ApiProblem;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.devfield.repoq.interview.project.ProjectSnapshotClient;
import dev.devfield.repoq.interview.security.InterviewAuthentication;
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
import org.junit.jupiter.params.provider.CsvSource;
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
        "devfield.project.auth.service-key=test-service-key",
        "devfield.project.deletion.poll-interval=PT1H",
        "devfield.project.deletion.auth-key-sha256=a8ef6f0b564b683fdd80875e39cad44b26c730994b9bca23b024624e34235526",
        "devfield.project.interview-key-sha256=72f151df5dd04aa4638b3f98bf13dd25bae9c980dc88b8afe67487096473fb72"
})
class PublicAnalysisIntegrationTest {
    private static final String X01_KEY = "x01-interview-fixture-key-for-tests-only";
    private static final String X03_KEY = "x03-auth-fixture-key-for-tests-only";

    @Autowired JdbcTemplate db;
    @Autowired TransactionTemplate tx;
    @Autowired ProjectDeletions deletions;
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
    UUID deletionSiblingOwner;
    final ObjectMapper json = new ObjectMapper();
    record Fixture(UUID project, UUID job) {}

    @BeforeEach void setup() throws Exception {
        owner = UUID.randomUUID(); server = new MockSourceServer(); client = server.client();
        worker = new AnalysisWorker(settings, jobs, snapshots, Optional.of(client), analyzer);
        when(verifier.verify(anyList())).thenAnswer(call -> client.verify(call.getArgument(0)));
        when(decoder.decode(anyString())).thenAnswer(call -> new Jwt(call.getArgument(0), Instant.now().minusSeconds(10),
                Instant.now().plusSeconds(300), Map.of("alg", "RS256"),
                Map.of("sub", "other".equals(call.getArgument(0)) ? UUID.randomUUID().toString() : owner.toString())));
        when(decoder.decode("expired")).thenThrow(new org.springframework.security.oauth2.jwt.BadJwtException("Expired token"));
    }
    @AfterEach void cleanup() {
        worker.stop(); server.close();
        cleanupOwner(owner);
        if (deletionSiblingOwner != null) cleanupOwner(deletionSiblingOwner);
    }

    private void cleanupOwner(UUID owner) {
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
        db.update("DELETE FROM project.repository_consent WHERE owner_user_id=?", owner);
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
    @Test void authenticatedOnlyCommitCannotBePinnedCollectedOrPublished() {
        Fixture f = fixture(1);
        var repo = server.repos.get("42");
        repo.authenticatedOnlyCommit = repo.head;
        client = server.client("fixture_with_private_access");
        worker = new AnalysisWorker(settings, jobs, snapshots, Optional.of(client), analyzer);

        assertTrue(worker.runJob(f.job()));
        assertEquals("FAILED", state(f));
        assertEquals("github_not_found", db.queryForObject("SELECT failure_code FROM project.analysis_job WHERE id=?", String.class, f.job()));
        assertFalse(db.queryForObject("SELECT repository_progress::text LIKE ? FROM project.analysis_job WHERE id=?",
                Boolean.class, "%" + repo.head + "%", f.job()));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.analysis_file_progress WHERE analysis_job_id=?", Integer.class, f.job()));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.analysis_snapshot WHERE project_id=?", Integer.class, f.project()));
        assertEquals(List.of("/repos/owner/repo42/git/ref/heads/main"), server.authenticatedPaths);
        assertTrue(server.paths.stream().anyMatch(path -> path.endsWith("/git/commits/" + repo.head)));
        assertFalse(server.paths.stream().anyMatch(path -> path.contains("/git/trees/") || path.contains("/git/blobs/")));
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

    private HttpResponse<String> interviewData(Fixture fixture, String query, String token, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/projects/" +
                fixture.project() + "/interview-data" + query)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("X-DevField-Service", "interview").header("X-DevField-Service-Key", key);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void interviewDataUsesStoredTopicsAndPreviousReadySnapshot(int count) throws Exception {
        Fixture f = fixture(count);
        worker.runJob(f.job()); assertEquals("READY", state(f));
        var response = interviewData(f, "", "owner", X01_KEY);
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        JsonNode snapshot = json.readTree(response.body());
        var interviewClient = new ProjectSnapshotClient(mock(InterviewAuthentication.class),
                "http://127.0.0.1:" + port, X01_KEY);
        var parsed = interviewClient.fetch(f.project(), "Bearer owner");
        assertEquals(snapshot, json.readTree(json.writeValueAsBytes(parsed)));
        assertEquals(count, snapshot.path("members").size());
        assertFalse(snapshot.has("coverage"));
        assertTrue(snapshot.path("relations").isEmpty());
        assertFalse(snapshot.path("topics").isEmpty());
        for (JsonNode topic : snapshot.path("topics")) {
            UUID stored = db.queryForObject("SELECT primary_evidence_id FROM project.available_topic WHERE snapshot_id=? AND topic_key=?",
                    UUID.class, UUID.fromString(snapshot.path("snapshotId").asText()), topic.path("key").asText());
            assertEquals(stored.toString(), topic.path("primaryEvidenceId").asText());
            assertTrue(snapshot.path("evidenceReferences").valueStream().anyMatch(ref ->
                    stored.toString().equals(ref.path("id").asText()) && "CODE_FACT".equals(ref.path("claimType").asText())));
        }
        UUID next = UUID.randomUUID();
        db.update("INSERT INTO project.analysis_job(id,project_id,trigger_kind,state,analysis_version,created_at) " +
                "VALUES (?,?,'USER_REANALYZE','RUNNING','test',clock_timestamp())", next, f.project());
        assertEquals(snapshot, json.readTree(interviewData(f, "?evidenceScope=PUBLIC_ONLY", "owner", X01_KEY).body()));
        db.update("UPDATE project.analysis_job SET state='FAILED' WHERE id=?", next);
        assertEquals(snapshot, json.readTree(interviewData(f, "", "owner", X01_KEY).body()));
    }

    @Test void interviewDataFailsClosedBeforeOrAfterPublicVerification() throws Exception {
        Fixture f = fixture(2);
        assertInterviewError(f, "", "owner", X01_KEY, 409, "snapshot_unavailable");
        worker.runJob(f.job()); assertEquals("READY", state(f));
        int before = server.paths.size();
        assertInterviewError(f, "", null, X01_KEY, 401, "invalid_token");
        assertInterviewError(f, "", "expired", X01_KEY, 401, "invalid_token");
        assertInterviewError(f, "", "owner", null, 403, "forbidden");
        assertInterviewError(f, "", "owner", "x".repeat(32), 403, "forbidden");
        assertInterviewError(f, "", "other", X01_KEY, 404, "project_not_found");
        assertInterviewError(f, "?evidenceScope=FULL", "owner", X01_KEY, 403, "evidence_scope_not_supported");
        assertInterviewError(f, "?evidenceScope=UNKNOWN", "owner", X01_KEY, 400, "invalid_request");
        assertInterviewError(f, "?evidenceScope=PUBLIC_ONLY&evidenceScope=FULL", "owner", X01_KEY, 400, "invalid_request");
        assertEquals(before, server.paths.size());

        server.repos.get("42").privateRepo = true;
        assertInterviewError(f, "", "owner", X01_KEY, 403, "repository_access_unavailable");
        server.repos.get("42").privateRepo = false;
        for (String failure : List.of("github_timeout", "github_rate_limited", "github_unavailable")) {
            doThrow(new GitHubSourceClient.Failure(failure)).when(verifier).verify(anyList());
            assertInterviewError(f, "", "owner", X01_KEY, 503, failure);
        }
        doAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            var proof = client.verify(call.getArgument(0));
            db.update("UPDATE project.registered_project SET membership_version=membership_version+1 WHERE id=?", f.project());
            return proof;
        }).when(verifier).verify(anyList());
        assertInterviewError(f, "", "owner", X01_KEY, 409, "snapshot_unavailable");
    }

    @Test void interviewDataRejectsPrivateMembershipBadTopicAndDeletingProject() throws Exception {
        Fixture f = fixture(2);
        worker.runJob(f.job()); assertEquals("READY", state(f));
        // Build a corrupted fixture through permitted inserts while the source job is temporarily RUNNING.
        db.update("UPDATE project.analysis_job SET state='RUNNING' WHERE id=?", f.job());
        db.update("INSERT INTO project.evidence_reference(id,snapshot_id,snapshot_member_id,github_repository_id," +
                "commit_sha,file_path,claim_type,summary) SELECT ?,snapshot_id,id,github_repository_id,commit_sha," +
                "'README.md','DOC_CLAIM','Documentation claim' FROM project.snapshot_member WHERE project_id=? LIMIT 1",
                UUID.randomUUID(), f.project());
        db.update("INSERT INTO project.available_topic(id,snapshot_id,topic_key,is_active,primary_evidence_id) " +
                "SELECT ?,e.snapshot_id,'INVALID_TOPIC',true,e.id FROM project.evidence_reference e " +
                "JOIN project.analysis_snapshot s ON s.id=e.snapshot_id " +
                "WHERE s.project_id=? AND e.claim_type='DOC_CLAIM'", UUID.randomUUID(), f.project());
        db.update("UPDATE project.analysis_job SET state='READY' WHERE id=?", f.job());
        assertInterviewError(f, "", "owner", X01_KEY, 409, "snapshot_unavailable");
        UUID connection = UUID.randomUUID();
        db.update("INSERT INTO project.github_connection(id,devfield_user_id) VALUES (?,?)", connection, owner);
        db.update("UPDATE project.project_repository SET visibility='PRIVATE',github_connection_id=? " +
                "WHERE project_id=? AND github_repository_id='43'", connection, f.project());
        int before = server.paths.size();
        assertInterviewError(f, "", "owner", X01_KEY, 403, "private_analysis_not_supported");
        db.update("UPDATE project.project_write_fence SET state='DELETING',deletion_job_id=? WHERE project_id=?",
                UUID.randomUUID(), f.project());
        assertInterviewError(f, "", "owner", X01_KEY, 409, "project_unavailable");
        assertEquals(before, server.paths.size());
    }

    private void assertInterviewError(Fixture f, String query, String token, String key, int status, String code) throws Exception {
        var response = interviewData(f, query, token, key);
        assertEquals(status, response.statusCode());
        if (status != 401 || !response.body().isEmpty())
            assertEquals(json.createObjectNode().put("code", code), json.readTree(response.body()));
    }

    @Test void projectDeletionRetriesAndRemovesAllProjectOwnedDataAfterX02Only() throws Exception {
        String createKey = "original-project-key";
        var upstream = server.add("42");
        var repository = new GitHubRepositoryClient.Repository(upstream.id, upstream.name, "fixture");
        var registration = projects.register(owner, List.of(repository), "Public fixture", createKey, "fixture-hash");
        UUID project = registration.id();
        UUID firstJob = db.queryForObject("SELECT id FROM project.analysis_job WHERE project_id=?", UUID.class, project);
        worker.runJob(firstJob);
        assertEquals("READY", state(new Fixture(project, firstJob)));
        UUID secondJob = queuedJob(project);
        server.resetWindow();
        worker.runJob(secondJob);
        assertEquals("READY", state(new Fixture(project, secondJob)));
        UUID runningJob = queuedJob(project);
        var execution = jobs.claim(runningJob);
        assertNotNull(execution);

        deletionSiblingOwner = UUID.randomUUID();
        UUID otherProject = projects.register(deletionSiblingOwner, repository, "Same GitHub repository").id();
        UUID connection = UUID.randomUUID();
        db.update("INSERT INTO project.github_connection(id,devfield_user_id) VALUES (?,?)", connection, owner);
        var sibling = projects.register(owner,
                new GitHubRepositoryClient.Repository("99", "owner/private", "private"), "Shared connection");
        db.update("UPDATE project.project_repository SET visibility='PRIVATE',github_connection_id=? WHERE project_id=?",
                connection, sibling.id());
        db.update("INSERT INTO project.project_repository(id,project_id,owner_user_id,github_repository_id," +
                "repository_full_name,visibility,github_connection_id) VALUES (?,?,?,'97','owner/private2','PRIVATE',?)",
                UUID.randomUUID(), project, owner, connection);
        db.update("INSERT INTO project.repository_consent(id,owner_user_id,github_repository_id,purpose,decision,policy_version) " +
                "VALUES (?,?,'42','PRIVATE_COLLECTION','GRANTED','test')", UUID.randomUUID(), owner);
        db.update("INSERT INTO project.project_repository(id,project_id,owner_user_id,github_repository_id," +
                "repository_full_name,visibility,removed_at) VALUES (?,?,?,'98','owner/removed','PUBLIC',now())",
                UUID.randomUUID(), project, owner);
        seedTemporaryAnalysis(project, runningJob);

        assertEquals(404, deleteProject(project, "other").statusCode());
        JsonNode pending;
        try (var callers = Executors.newFixedThreadPool(2)) {
            var start = new CyclicBarrier(2);
            Callable<HttpResponse<String>> request = () -> {
                start.await(5, TimeUnit.SECONDS);
                return deleteProject(project, "owner");
            };
            var first = callers.submit(request);
            var second = callers.submit(request);
            var accepted = first.get(10, TimeUnit.SECONDS);
            var repeated = second.get(10, TimeUnit.SECONDS);
            assertEquals(202, accepted.statusCode());
            assertEquals(202, repeated.statusCode());
            pending = json.readTree(accepted.body());
            assertEquals(pending, json.readTree(repeated.body()));
        }
        assertEquals("CANCELLED", state(new Fixture(project, runningJob)));
        assertThrows(RuntimeException.class, () -> jobs.heartbeat(execution));
        assertEquals(0, countProjectRows("analysis_repository_checkpoint", project));
        assertEquals(0, countProjectRows("analysis_file_progress", project));
        int calls = server.paths.size();
        var detail = get("/api/projects/" + project);
        assertEquals("DELETING", detail.path("projectStatus").asText());
        assertTrue(detail.path("latestReadySnapshot").isNull());
        assertTrue(detail.path("repositories").get(0).path("commitSha").isNull());
        assertTrue(detail.path("repositories").get(0).path("fullName").isNull());
        assertFalse(detail.path("canInterview").asBoolean());
        assertEquals(calls, server.paths.size());
        assertInterviewError(new Fixture(project, runningJob), "", "owner", X01_KEY, 409, "project_unavailable");

        var status = new AtomicInteger(503);
        var validWire = new AtomicBoolean();
        var responses = new AtomicInteger();
        var loseResponse = new AtomicBoolean();
        String key = "x02-dedicated-loopback-key-for-tests";
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setExecutor(threads);
            http.createContext("/", exchange -> {
                try (exchange) {
                    var body = json.readTree(exchange.getRequestBody());
                    validWire.set(exchange.getRequestMethod().equals("DELETE") &&
                            exchange.getRequestURI().getPath().equals("/internal/projects/" + project + "/interviews") &&
                            "project".equals(exchange.getRequestHeaders().getFirst("X-DevField-Service")) &&
                            key.equals(exchange.getRequestHeaders().getFirst("X-DevField-Service-Key")) &&
                            exchange.getRequestHeaders().getFirst("Authorization") == null &&
                            body.equals(json.createObjectNode().put("projectId", project.toString())
                                    .put("ownerUserId", owner.toString())
                                    .put("deletionJobId", pending.path("deletionJobId").asText())));
                    if (loseResponse.get()) {
                        responses.incrementAndGet();
                        try { Thread.sleep(1400); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                    exchange.sendResponseHeaders(status.get(), -1);
                }
            });
            http.start();
            try {
                var receiver = new InterviewDeletionClient("http://127.0.0.1:" + http.getAddress().getPort(), key, 1);
                for (var failure : Map.of(503, "x02_unavailable", 409, "x02_deletion_conflict",
                        401, "x02_auth_rejected", 403, "x02_auth_rejected").entrySet()) {
                    status.set(failure.getKey());
                    runDeletion(project, receiver);
                    assertEquals(failure.getValue(), deletionFailure(project));
                    assertEquals(2, countProjectRows("analysis_snapshot", project));
                    assertTrue(validWire.get());
                }
                status.set(204);
                loseResponse.set(true);
                runDeletion(project, receiver);
                assertEquals(1, responses.get());
                assertEquals("x02_unavailable", deletionFailure(project));
                assertEquals(2, countProjectRows("analysis_snapshot", project));
                loseResponse.set(false);

                String blocker = "project.test_delete_" + UUID.randomUUID().toString().replace("-", "");
                db.execute("CREATE TABLE " + blocker + " (id uuid REFERENCES project.registered_project(id))");
                try {
                    db.update("INSERT INTO " + blocker + " VALUES (?)", project);
                    runDeletion(project, receiver);
                    assertEquals("project_cleanup_failed", deletionFailure(project));
                    assertEquals(2, countProjectRows("analysis_snapshot", project));
                } finally {
                    db.execute("DROP TABLE " + blocker);
                }
                runDeletion(project, receiver);
                assertTrue(validWire.get());
                assertEquals(204, deleteProject(project, "owner").statusCode());
                assertEquals(404, deleteProject(project, "other").statusCode());
                assertEquals(404, call("/api/projects/" + project, "owner").statusCode());
                for (String table : List.of("analysis_snapshot", "snapshot_member", "analysis_job", "project_repository"))
                    assertEquals(0, countProjectRows(table, project), table);
                assertEquals(1, countProjectRows("project_repository", otherProject));
                assertEquals(1, countProjectRows("project_repository", sibling.id()));
                assertEquals(1, db.queryForObject("SELECT count(*) FROM project.github_connection WHERE id=?", Integer.class, connection));
                assertEquals(1, db.queryForObject("SELECT count(*) FROM project.repository_consent WHERE owner_user_id=?",
                        Integer.class, owner));
                assertEquals("idempotency_key_conflict",
                        assertThrows(ApiProblem.class, () -> projects.replayRegistration(owner, createKey, "fixture-hash")).code());
                assertNotEquals(project, projects.register(owner, List.of(repository), "New", "new-project-key", "new-hash").id());
                assertThrows(RuntimeException.class, () -> jobs.heartbeat(execution));
            } finally {
                http.stop(0);
            }
        }
    }

    @Test void deletingProjectDuringX01VerificationRejectsSnapshot() throws Exception {
        Fixture f = fixture(1);
        worker.runJob(f.job());
        assertEquals("READY", state(f));
        doAnswer(call -> {
            var proof = client.verify(call.getArgument(0));
            deletions.request(owner, f.project());
            return proof;
        }).when(verifier).verify(anyList());
        assertInterviewError(f, "", "owner", X01_KEY, 409, "project_unavailable");
        assertEquals("DELETING", get("/api/projects/" + f.project()).path("projectStatus").asText());
    }

    @Test void projectDeletionLeaseBlocksOtherInstancesAndIgnoresLateFailure() throws Exception {
        Fixture f = fixture(1);
        var pending = deletions.request(owner, f.project());
        var requests = new AtomicInteger();
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setExecutor(threads);
            http.createContext("/", exchange -> {
                try (exchange) {
                    var command = json.readTree(exchange.getRequestBody());
                    if (!pending.deletionJobId().toString().equals(command.path("deletionJobId").asText())) {
                        exchange.sendResponseHeaders(409, -1);
                        return;
                    }
                    if (requests.incrementAndGet() == 1) {
                        firstEntered.countDown();
                        try { releaseFirst.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        exchange.sendResponseHeaders(503, -1);
                    } else {
                        exchange.sendResponseHeaders(204, -1);
                    }
                }
            });
            http.start();
            try {
                var client = new InterviewDeletionClient("http://127.0.0.1:" + http.getAddress().getPort(),
                        "x02-dedicated-loopback-key-for-tests", 8);
                var first = threads.submit(() -> new ProjectDeletions(db, tx, client, 1).poll());
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
                new ProjectDeletions(db, tx, client, 1).poll();
                assertEquals(1, requests.get());
                // Expire the persisted claim as if the first instance had stopped responding.
                runDeletion(f.project(), client);
                assertEquals(2, requests.get());
                assertEquals(204, deleteProject(f.project(), "owner").statusCode());
                releaseFirst.countDown();
                first.get(5, TimeUnit.SECONDS);
                assertNull(deletionFailure(f.project()));
                assertEquals(204, deleteProject(f.project(), "owner").statusCode());
            } finally {
                releaseFirst.countDown();
                http.stop(0);
            }
        }
    }

    @Test void x03RequiresDedicatedAuthCredentialAndIsIdempotent() throws Exception {
        UUID job = UUID.randomUUID();
        deletionSiblingOwner = UUID.randomUUID();
        assertEquals(401, deleteUser(owner, owner, job, "interview", X03_KEY));
        assertEquals(401, deleteUser(owner, owner, job, "auth", X01_KEY));
        assertEquals(401, deleteUser(owner, owner, job, "auth", null));
        assertEquals(400, deleteUser(owner, deletionSiblingOwner, job, "auth", X03_KEY));
        assertEquals(204, deleteUser(owner, owner, job, "auth", X03_KEY));
        assertEquals(204, deleteUser(owner, owner, job, "auth", X03_KEY));
        assertEquals(409, deleteUser(owner, owner, UUID.randomUUID(), "auth", X03_KEY));
        assertEquals(409, deleteUser(deletionSiblingOwner, deletionSiblingOwner, job, "auth", X03_KEY));
    }

    @Test void x03TakesOverP06AndRetriesCleanupWithoutReopeningWrites() throws Exception {
        Fixture f = fixture(1);
        worker.runJob(f.job());
        assertEquals("READY", state(f));
        var pending = deletions.request(owner, f.project());
        deletionSiblingOwner = UUID.randomUUID();
        UUID sibling = projects.register(deletionSiblingOwner, new GitHubRepositoryClient.Repository(
                "42", server.repos.get("42").name, "fixture"), "Other owner").id();
        Fixture active = fixtureWithOtherIds();
        var execution = jobs.claim(active.job());
        assertNotNull(execution);
        db.update("INSERT INTO project.github_connection(id,devfield_user_id) VALUES (?,?)", UUID.randomUUID(), owner);
        db.update("INSERT INTO project.repository_consent(id,owner_user_id,github_repository_id,purpose,decision,policy_version) " +
                "VALUES (?,?,'42','PRIVATE_COLLECTION','GRANTED','test')", UUID.randomUUID(), owner);
        UUID userJob = UUID.randomUUID();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var requests = new AtomicInteger();
        String blocker = "project.test_delete_" + UUID.randomUUID().toString().replace("-", "");
        db.execute("CREATE TABLE " + blocker + " (id uuid REFERENCES project.registered_project(id))");
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            db.update("INSERT INTO " + blocker + " VALUES (?)", f.project());
            var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setExecutor(threads);
            http.createContext("/", exchange -> {
                try (exchange) {
                    requests.incrementAndGet();
                    entered.countDown();
                    try { release.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    exchange.sendResponseHeaders(204, -1);
                }
            });
            http.start();
            try {
                var client = new InterviewDeletionClient("http://127.0.0.1:" + http.getAddress().getPort(),
                        "x02-dedicated-loopback-key-for-tests", 12);
                var poller = new ProjectDeletions(db, tx, client, 1);
                var inFlight = threads.submit(poller::poll);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(503, deleteUser(owner, owner, userJob, "auth", X03_KEY));
                assertEquals("DELETING", db.queryForObject("SELECT state FROM project.project_write_fence " +
                        "WHERE scope='USER' AND owner_user_id=?", String.class, owner));
                assertEquals(pending.deletionJobId(), db.queryForObject("SELECT deletion_job_id " +
                        "FROM project.project_write_fence WHERE project_id=?", UUID.class, f.project()));
                assertThrows(ApiProblem.class, () -> deletions.request(owner, f.project()));
                assertEquals("CANCELLED", state(active));
                assertThrows(RuntimeException.class, () -> jobs.heartbeat(execution));
                release.countDown();
                inFlight.get(5, TimeUnit.SECONDS);
                assertEquals(1, countProjectRows("analysis_snapshot", f.project()));
                runDeletion(f.project(), client);
                assertEquals(1, requests.get());
                UUID otherPending = projects.register(deletionSiblingOwner,
                        new GitHubRepositoryClient.Repository("777", "sample/pending", "fixture"), "Other pending").id();
                deletions.request(deletionSiblingOwner, otherPending);
                poller.poll();
                assertEquals(2, requests.get());
                assertEquals(0, countProjectRows("project_repository", otherPending));
                assertEquals(409, deleteUser(owner, owner, UUID.randomUUID(), "auth", X03_KEY));
            } finally {
                release.countDown();
                http.stop(0);
            }
        } finally {
            db.execute("DROP TABLE " + blocker);
        }
        assertEquals(204, deleteUser(owner, owner, userJob, "auth", X03_KEY));
        assertEquals(204, deleteUser(owner, owner, userJob, "auth", X03_KEY));
        for (String table : List.of("analysis_snapshot", "snapshot_member", "analysis_job", "project_repository"))
            assertEquals(0, countProjectRows(table, f.project()), table);
        assertEquals(0, countProjectRows("analysis_job", active.project()));
        assertEquals(1, countProjectRows("project_repository", sibling));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.github_connection WHERE devfield_user_id=?",
                Integer.class, owner));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.repository_consent WHERE owner_user_id=?",
                Integer.class, owner));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.project_write_fence " +
                "WHERE scope='PROJECT' AND owner_user_id=?", Integer.class, owner));
    }

    private int deleteUser(UUID path, UUID subject, UUID job, String caller, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/users/" + path + "/data"))
                .header("Content-Type", "application/json").header("X-DevField-Service", caller)
                .method("DELETE", HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "userId", subject, "deletionJobId", job, "state", "DELETING"))));
        if (key != null) request.header("X-DevField-Service-Key", key);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test void p03ReturnsRedactedDeletionStateWhenPublicVerificationRaces() throws Exception {
        Fixture f = fixture(1);
        worker.runJob(f.job());
        assertEquals("READY", state(f));
        doAnswer(call -> {
            deletions.request(owner, f.project());
            throw new GitHubSourceClient.Failure("github_unavailable");
        }).when(verifier).verify(anyList());
        var result = get("/api/projects/" + f.project());
        assertEquals("DELETING", result.path("projectStatus").asText());
        assertTrue(result.path("latestReadySnapshot").isNull());
        assertTrue(result.path("repositories").get(0).path("commitSha").isNull());
    }

    @Test void deletingProjectJustBeforeSnapshotPublicationDiscardsLateWorkerResult() {
        Fixture f = fixture(1);
        doAnswer(call -> {
            var proof = client.verify(call.getArgument(0));
            deletions.request(owner, f.project());
            return proof;
        }).when(verifier).verify(anyList());
        assertTrue(worker.runJob(f.job()));
        assertEquals("CANCELLED", state(f));
        assertEquals(0, countProjectRows("analysis_snapshot", f.project()));
        assertEquals(0, countProjectRows("analysis_repository_checkpoint", f.project()));
    }

    private HttpResponse<String> deleteProject(UUID project, String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/projects/" + project))
                .header("Authorization", "Bearer " + token).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private UUID queuedJob(UUID project) {
        UUID job = UUID.randomUUID();
        db.update("""
                INSERT INTO project.analysis_job(id,project_id,trigger_kind,state,analysis_version,repository_progress)
                SELECT ?,?,'USER_REANALYZE','QUEUED','v1',
                       jsonb_agg(jsonb_build_object('repositoryId',id,'state','QUEUED'))
                FROM project.project_repository WHERE project_id=? AND removed_at IS NULL
                """, job, project, project);
        return job;
    }

    private void seedTemporaryAnalysis(UUID project, UUID job) {
        db.update("""
                INSERT INTO project.analysis_repository_checkpoint(analysis_job_id,project_id,project_repository_id,
                    membership_version,github_repository_id,commit_sha,analysis_version,analyzer_policy_version,
                    completed_file_count,completed_bytes,draft,expires_at)
                SELECT ?,project_id,id,1,github_repository_id,?,'v1','fixture',1,1,
                    '{"members":[{}],"coverage":[{}],"evidence":[],"topics":[],"roles":[],"relations":[]}'::jsonb,
                    clock_timestamp()+interval '1 hour'
                FROM project.project_repository WHERE project_id=? AND github_repository_id='42'
                """, job, MockSourceServer.SHA, project);
        db.update("""
                INSERT INTO project.analysis_file_progress(analysis_job_id,project_id,project_repository_id,
                    membership_version,github_repository_id,commit_sha,blob_sha,file_path,expected_bytes,
                    policy_version,attempt_count,download_count,state,expires_at)
                SELECT ?,project_id,id,1,github_repository_id,?,?,'src/App.java',1,'fixture',1,1,'RESERVED',
                    clock_timestamp()+interval '1 hour'
                FROM project.project_repository WHERE project_id=? AND github_repository_id='42'
                """, job, MockSourceServer.SHA, MockSourceServer.SHA, project);
    }

    private int countProjectRows(String table, UUID project) {
        return db.queryForObject("SELECT count(*) FROM project." + table + " WHERE project_id=?", Integer.class, project);
    }

    private String deletionFailure(UUID project) {
        return db.queryForObject("SELECT deletion_failure_code FROM project.project_write_fence WHERE project_id=?",
                String.class, project);
    }

    private void runDeletion(UUID project, InterviewDeletionClient receiver) {
        db.update("UPDATE project.project_write_fence SET deletion_next_attempt_at=clock_timestamp() WHERE project_id=?", project);
        // A fresh processor has no in-memory state from the previous attempt.
        new ProjectDeletions(db, tx, receiver, 1).poll();
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
    @ParameterizedTest @ValueSource(strings={
            "class Example<HttpClient> { HttpClient value; }",
            "class Parent { static class HttpClient {} } class Child extends Parent { HttpClient value; }"})
    void shadowingOnlyIsInsufficientRatherThanReady(String declaration) {
        Fixture f = fixture(1);
        var repo = server.repos.get("42");
        repo.files.clear();
        repo.files.put("src/Example.java", "import java.net.http.HttpClient; " + declaration);
        worker.runJob(f.job());
        assertEquals("INSUFFICIENT_EVIDENCE", state(f));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.analysis_snapshot WHERE project_id=?", Integer.class, f.project()));
    }

    @ParameterizedTest @ValueSource(ints = {0, 40, 60})
    void fiveRepositoriesReservePublicationAndShareRemainingQuota(int advertisedRemaining) throws Exception {
        Fixture f = fixture(5);
        boolean pinned = advertisedRemaining == 60;
        if (pinned) db.update("UPDATE project.analysis_job SET repository_progress=(SELECT jsonb_agg(" +
                "jsonb_set(p,'{commitSha}',to_jsonb(?::text))) FROM jsonb_array_elements(repository_progress) p) WHERE id=?",
                MockSourceServer.SHA, f.job());
        for (var repo : server.repos.values()) {
            repo.files.clear();
            for (int i = 0; i < 8; i++) repo.files.put("src/App" + i + ".java", MockSourceServer.JAVA);
        }
        if (advertisedRemaining == 40) server.headers.put("X-RateLimit-Remaining", "40");
        worker.runJob(f.job());
        assertEquals("READY", state(f));
        assertTrue(server.paths.size() <= 60);
        assertEquals(pinned ? 30 : advertisedRemaining == 0 ? 25 : 6, server.contentRequests.get());
        assertTrue(server.paths.subList(server.paths.size() - 5, server.paths.size()).stream().allMatch(p -> p.startsWith("/repositories/")));
        if (pinned) assertTrue(server.paths.stream().noneMatch(p -> p.contains("/git/ref/")));
        var coverage = json.readTree(db.queryForObject("SELECT coverage::text FROM project.analysis_snapshot WHERE project_id=?", String.class, f.project()));
        int min = 40, max = 0;
        for (JsonNode entry : coverage) {
            int count = entry.path("collectedFiles").intValue();
            min = Math.min(min, count); max = Math.max(max, count);
            assertTrue(entry.path("exclusions").has("api_budget"));
        }
        assertTrue(min > 0); assertTrue(max - min <= 1);
        assertEquals(0, server.authorizationHeaders.get());
        assertFalse(worker.runJob(f.job()));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.analysis_snapshot WHERE project_id=?", Integer.class, f.project()));
    }

    @ParameterizedTest @CsvSource({"60,0", "60,90000", "20,0"})
    void twentyRepositoriesResumeAcrossWindowsWithoutDownloadingCompletedBlobs(int requestLimit, int padding) throws Exception {
        Fixture f = fixture(20);
        worker = new AnalysisWorker(new AnalysisSettings(false, 3, 60, 300, requestLimit), jobs, snapshots, Optional.of(client), analyzer);
        for (var repo : server.repos.values()) {
            repo.files.clear();
            for (int i = 0; i < 8; i++) repo.files.put("src/App" + i + ".java", MockSourceServer.JAVA + " ".repeat(padding));
        }
        int windows = 0;
        Set<String> completedBlobPaths = new HashSet<>();
        while (!"READY".equals(state(f)) && windows++ < 12) {
            int start = server.paths.size();
            worker.runJob(f.job());
            List<String> paths = List.copyOf(server.paths.subList(start, server.paths.size()));
            assertTrue(paths.size() <= requestLimit);
            Set<String> thisWindow = new HashSet<>(paths.stream().filter(p -> p.contains("/git/blobs/")).toList());
            assertTrue(Collections.disjoint(completedBlobPaths, thisWindow));
            completedBlobPaths.addAll(thisWindow);
            assertEquals(0, db.queryForObject("SELECT failure_count FROM project.analysis_job WHERE id=?", Integer.class, f.job()));
            if (!"READY".equals(state(f))) {
                assertEquals("QUEUED", state(f));
                assertTrue(db.queryForObject("SELECT count(*) FROM project.analysis_repository_checkpoint WHERE analysis_job_id=?", Integer.class, f.job()) > 0);
                assertTrue(db.queryForObject("SELECT sum(completed_bytes)<=2097152 AND sum(completed_file_count)<=40 FROM project.analysis_repository_checkpoint WHERE analysis_job_id=?", Boolean.class, f.job()));
                server.resetWindow();
                db.update("UPDATE project.analysis_job SET next_attempt_at=clock_timestamp() WHERE id=?", f.job());
                // A new worker instance must use persisted results, not in-memory collected files.
                worker = new AnalysisWorker(new AnalysisSettings(false, 3, 60, 300, requestLimit), jobs, snapshots, Optional.of(client), analyzer);
            }
        }
        assertEquals("READY", state(f)); assertTrue(windows > 1);
        assertTrue(server.contentRequests.get() <= 40);
        assertEquals(20, completedBlobPaths.size());
        assertTrue(server.paths.subList(server.paths.size() - 20, server.paths.size()).stream().allMatch(p -> p.startsWith("/repositories/")));
        assertEquals(20, db.queryForObject("SELECT count(*) FROM project.snapshot_member WHERE project_id=?", Integer.class, f.project()));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.analysis_repository_checkpoint WHERE analysis_job_id=?", Integer.class, f.job()));
        assertFalse(worker.runJob(f.job()));
        assertEquals(0, server.authorizationHeaders.get());
    }

    @Test void checkpointPublicToPrivateTransitionBlocksReuseAndDeletesResults() {
        Fixture f = fixture(1);
        server.beforeBlob = () -> { server.allStatus = 429; server.headers.put("Retry-After", "120"); };
        worker.runJob(f.job()); assertEquals("QUEUED", state(f));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.analysis_file_progress WHERE analysis_job_id=?", Integer.class, f.job()));
        int blobs = server.contentRequests.get();
        server.allStatus = 0; server.beforeBlob = () -> {}; server.repos.get("42").privateRepo = true;
        server.resetWindow();
        db.update("UPDATE project.analysis_job SET next_attempt_at=clock_timestamp() WHERE id=?", f.job());
        worker.runJob(f.job()); assertEquals("FAILED", state(f));
        assertEquals(blobs, server.contentRequests.get());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.analysis_repository_checkpoint WHERE analysis_job_id=?", Integer.class, f.job()));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.analysis_snapshot WHERE project_id=?", Integer.class, f.project()));
    }

    @Test void fourHttpRateLimitWindowsResumeWithoutSpendingFailureBudget() {
        Fixture f = fixture(1);
        server.allStatus = 429;
        for (int i = 0; i < 4; i++) {
            server.headers.put("Retry-After", "120");
            worker.runJob(f.job()); assertEquals("QUEUED", state(f));
            assertEquals(0, db.queryForObject("SELECT failure_count FROM project.analysis_job WHERE id=?", Integer.class, f.job()));
            server.resetWindow();
            db.update("UPDATE project.analysis_job SET next_attempt_at=clock_timestamp() WHERE id=?", f.job());
        }
        server.allStatus = 0;
        worker.runJob(f.job()); assertEquals("READY", state(f));
        assertEquals(5, db.queryForObject("SELECT attempt_count FROM project.analysis_job WHERE id=?", Integer.class, f.job()));
    }

    @Test void repeatedRateWaitDoesNotSpendFailuresAndLifetimeStopsWaiting() {
        Fixture f = fixture(1);
        for (int i = 0; i < 5; i++) {
            var run = jobs.claim(f.job()); assertNotNull(run);
            jobs.retry(run, "github_rate_limited", 60);
            assertEquals(0, db.queryForObject("SELECT failure_count FROM project.analysis_job WHERE id=?", Integer.class, f.job()));
            db.update("UPDATE project.analysis_job SET next_attempt_at=clock_timestamp() WHERE id=?", f.job());
        }
        worker.runJob(f.job()); assertEquals("READY", state(f));
        Fixture expired = fixtureWithOtherIds();
        db.update("UPDATE project.analysis_job SET created_at=clock_timestamp()-interval '25 hours' WHERE id=?", expired.job());
        assertFalse(worker.runJob(expired.job())); assertEquals("FAILED", state(expired));
        assertEquals("analysis_lifetime_exceeded", db.queryForObject("SELECT failure_code FROM project.analysis_job WHERE id=?", String.class, expired.job()));
    }

    Fixture fixtureWithOtherIds() {
        var repo = server.add("9999");
        var project = projects.register(owner, List.of(new GitHubRepositoryClient.Repository(repo.id, repo.name, "fixture")), "Another", null, null);
        return new Fixture(project.id(), db.queryForObject("SELECT id FROM project.analysis_job WHERE project_id=?", UUID.class, project.id()));
    }

    @Test void filePipelinePreservesValidatedResult() {
        Fixture f=fixture(1);var run=jobs.claim(f.job());var target=jobs.targets(run).getFirst();
        var budget=new GitHubSourceClient.Budget(60,()->jobs.heartbeat(run));
        var plan=client.prepare(target,40,2097152,budget,sha->jobs.progress(run,new AnalysisJobs.Progress(target.repositoryId(),"RUNNING",sha,null)));
        var access=client.openCollection(plan,budget);
        snapshots.files().read(run,plan,access.proof());
        var fitted=client.fitPlans(List.of(plan),budget,1).getFirst();
        var entry=fitted.files().getFirst();
        var download=client.download(access,entry,budget,()->snapshots.files().reserve(run,plan,entry));
        var draft=analyzer.analyze(List.of(new GitHubSourceClient.Collected(target,plan.commitSha(),List.of(download.file()),Map.of())));
        snapshots.files().collect(run,plan,entry,draft);
        var proof=client.checkpointProof(plan,budget);
        var result=snapshots.files().merge(run,plan,Map.of(),proof);
        snapshots.checkpoint(run,result.draft(),result.bytes(),proof);
        snapshots.publish(run);assertEquals("READY",state(f));
    }

    @Test void thirtyDownloadedFilesSurviveRateLimitWithoutRedownload() {
        Fixture f = fixture(1); var repo = server.repos.get("42"); repo.files.clear();
        for (int i = 0; i < 40; i++) repo.files.put("src/App" + i + ".java", MockSourceServer.JAVA + " ".repeat(49000));
        server.beforeBlob = () -> { if (server.contentRequests.get() == 30) server.headers.put("X-RateLimit-Remaining", "0"); };
        worker.runJob(f.job()); assertEquals("QUEUED", state(f)); assertEquals(30, server.contentRequests.get());
        server.resetWindow(); server.beforeBlob = () -> {};
        db.update("UPDATE project.analysis_job SET next_attempt_at=clock_timestamp() WHERE id=?", f.job());
        worker.runJob(f.job()); assertEquals("READY", state(f)); assertEquals(40, server.contentRequests.get());
        assertEquals(40,db.queryForObject("SELECT download_count FROM project.analysis_job WHERE id=?",Integer.class,f.job()));
        assertTrue(db.queryForObject("SELECT download_bytes<=2097152 FROM project.analysis_job WHERE id=?",Boolean.class,f.job()));
    }

    @Test void resumedLargeBodiesRemainWithinCumulativeByteCeiling() {
        Fixture f=fixture(1);var repo=server.repos.get("42");repo.files.clear();
        for(int i=0;i<40;i++) repo.files.put("src/Large"+i+".java",MockSourceServer.JAVA+" ".repeat(90000));
        server.beforeBlob=()->{if(server.contentRequests.get()==15)server.headers.put("X-RateLimit-Remaining","0");};
        worker.runJob(f.job());assertEquals("QUEUED",state(f));assertEquals(15,server.contentRequests.get());
        server.resetWindow();server.beforeBlob=()->{};
        db.update("UPDATE project.analysis_job SET next_attempt_at=clock_timestamp() WHERE id=?",f.job());
        worker.runJob(f.job());assertEquals("READY",state(f));
        int count=server.contentRequests.get();assertEquals(23,count);
        assertEquals(count,db.queryForObject("SELECT download_count FROM project.analysis_job WHERE id=?",Integer.class,f.job()));
        assertEquals(count*(90000+MockSourceServer.JAVA.getBytes(java.nio.charset.StandardCharsets.UTF_8).length),
                db.queryForObject("SELECT download_bytes FROM project.analysis_job WHERE id=?",Integer.class,f.job()));
        assertTrue(count*(90000+MockSourceServer.JAVA.length())<=2097152);
    }

    @Test void checkpointRefreshRateWaitKeepsSealedFilesWithoutRedownload() {
        Fixture f=fixture(1);
        server.beforeMetadata=()->{ if(server.contentRequests.get()>0) {server.allStatus=429;server.headers.put("Retry-After","60");} };
        worker.runJob(f.job()); assertEquals("QUEUED",state(f));
        assertEquals(0,db.queryForObject("SELECT failure_count FROM project.analysis_job WHERE id=?",Integer.class,f.job()));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM project.analysis_file_progress WHERE analysis_job_id=?",Integer.class,f.job()));
        server.beforeMetadata=()->{};server.allStatus=0;server.resetWindow();
        db.update("UPDATE project.analysis_job SET next_attempt_at=clock_timestamp() WHERE id=?",f.job());
        worker.runJob(f.job());assertEquals("READY",state(f));assertEquals(1,server.contentRequests.get());
    }

    @Test void checkpointRefreshPrivateCancelAndExpiredLeaseCannotPublish() {
        for(String change:List.of("private","cancel","lease")) {
            Fixture f=fixtureWithOtherIds(); var repo=server.repos.get("9999");
            int before=server.contentRequests.get();
            server.beforeMetadata=()->{if(server.contentRequests.get()>before) {
                switch(change) {
                    case "private" -> repo.privateRepo=true;
                    case "cancel" -> db.update("UPDATE project.analysis_job SET state='CANCELLED' WHERE id=?",f.job());
                    case "lease" -> db.update("UPDATE project.analysis_job SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",f.job());
                }
            }};
            worker.runJob(f.job());
            assertNotEquals("READY",state(f));
            assertEquals(0,db.queryForObject("SELECT count(*) FROM project.analysis_snapshot WHERE project_id=?",Integer.class,f.project()));
            assertEquals(0,db.queryForObject("SELECT count(*) FROM project.analysis_repository_checkpoint WHERE analysis_job_id=?",Integer.class,f.job()));
            server.beforeMetadata=()->{};
            // Remove this isolated fixture before reusing its GitHub ID.
            db.update("DELETE FROM project.analysis_job WHERE id=?",f.job());
            db.update("DELETE FROM project.project_repository WHERE project_id=?",f.project());
            db.update("DELETE FROM project.registered_project WHERE id=?",f.project());
        }
    }

    @ParameterizedTest @ValueSource(ints={46,47,48})
    void slowCollectionRequestBoundaryPreservesProgress(int limit) {
        Fixture f=fixture(1);var repo=server.repos.get("42");repo.files.clear();
        for(int i=0;i<40;i++)repo.files.put("src/App"+i+".java",MockSourceServer.JAVA+" // file "+i);
        worker=new AnalysisWorker(new AnalysisSettings(false,3,60,300,limit),jobs,snapshots,Optional.of(client),analyzer);
        server.beforeBlob=()->{try{Thread.sleep(800);}catch(InterruptedException e){Thread.currentThread().interrupt();}};
        worker.runJob(f.job());
        assertTrue(server.paths.size()<=limit);
        assertEquals(0,db.queryForObject("SELECT failure_count FROM project.analysis_job WHERE id=?",Integer.class,f.job()));
        if(limit<48) {
            assertEquals("QUEUED",state(f));
            int completed=server.contentRequests.get();
            assertTrue(completed>0 && completed<40);
            assertEquals(completed,db.queryForObject("SELECT count(*) FROM project.analysis_file_progress WHERE analysis_job_id=? AND state='COLLECTED'",Integer.class,f.job()));
            assertEquals("analysis_window_complete",db.queryForObject("SELECT failure_code FROM project.analysis_job WHERE id=?",String.class,f.job()));
            assertTrue(db.queryForObject("SELECT next_attempt_at>clock_timestamp() FROM project.analysis_job WHERE id=?",Boolean.class,f.job()));
            var previous=List.copyOf(server.paths);server.beforeBlob=()->{};
            db.update("UPDATE project.analysis_job SET next_attempt_at=clock_timestamp() WHERE id=?",f.job());
            worker=new AnalysisWorker(new AnalysisSettings(false,3,60,300,limit),jobs,snapshots,Optional.of(client),analyzer);
            worker.runJob(f.job());
            assertTrue(java.util.Collections.disjoint(previous.stream().filter(p->p.contains("/git/blobs/")).toList(),
                server.paths.subList(previous.size(),server.paths.size()).stream().filter(p->p.contains("/git/blobs/")).toList()));
        }
        assertEquals("READY",state(f));assertEquals(40,server.contentRequests.get());
        assertEquals(0,db.queryForObject("SELECT failure_count FROM project.analysis_job WHERE id=?",Integer.class,f.job()));
        assertTrue(db.queryForObject("SELECT download_count=40 AND download_bytes<=2097152 FROM project.analysis_job WHERE id=?",Boolean.class,f.job()));
    }

    @Test void fortySlowFilesRefreshPublicProofBeforeCheckpoint() {
        Fixture f = fixture(1); var repo = server.repos.get("42"); repo.files.clear();
        for (int i = 0; i < 40; i++) repo.files.put("src/App" + i + ".java", MockSourceServer.JAVA);
        server.beforeBlob = () -> {
            try { Thread.sleep(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        worker.runJob(f.job()); assertEquals("READY", state(f)); assertEquals(40, server.contentRequests.get());
        assertTrue(server.paths.stream().filter(p -> p.equals("/repositories/42")).count() >= 5);
    }

    @Test void quotaConsumedDuringCollectionWaitsWithoutClaimingAgainAndPinsCommit() {
        Fixture f = fixture(1);
        server.beforeBlob = () -> server.headers.put("X-RateLimit-Remaining", "0");
        worker.runJob(f.job());
        assertEquals("QUEUED", state(f));
        assertEquals("github_rate_limited", db.queryForObject("SELECT failure_code FROM project.analysis_job WHERE id=?", String.class, f.job()));
        int calls = server.paths.size();
        assertFalse(worker.runJob(f.job()));
        assertEquals(calls, server.paths.size());
        assertEquals(1, db.queryForObject("SELECT attempt_count FROM project.analysis_job WHERE id=?", Integer.class, f.job()));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.analysis_snapshot WHERE project_id=?", Integer.class, f.project()));
        server.headers.clear(); server.beforeBlob = () -> {}; server.paths.clear();
        client = server.client(); worker = new AnalysisWorker(settings, jobs, snapshots, Optional.of(client), analyzer);
        db.update("UPDATE project.analysis_job SET next_attempt_at=clock_timestamp() WHERE id=?", f.job());
        worker.runJob(f.job());
        assertEquals("READY", state(f));
        assertTrue(server.paths.stream().noneMatch(p -> p.contains("/git/ref/")));
    }

    @Test void queuedRateWaitCannotReviveCancelledJob() {
        Fixture f = fixture(1);
        db.update("UPDATE project.analysis_job SET state='CANCELLED' WHERE id=?", f.job());
        assertEquals("CANCELLED", state(f));
        assertFalse(worker.runJob(f.job()));
        assertTrue(server.paths.isEmpty());
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
