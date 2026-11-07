package dev.devfield.repoq.interview.session;

import static dev.devfield.repoq.interview.session.InterviewSessions.Mode.FREE;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.devfield.repoq.interview.project.ProjectSnapshot;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "devfield.interview.deletion.project-key-sha256=4acb595e5ada8c9a973db7268d56e9ed27bc097157ce781b7ef0ce5ef401e95d",
        "devfield.interview.deletion.auth-key-sha256=9dc1622a4d732fd2b95d9733ff02dbdf1364f60465c4d10a8badcf779cab4c3e"
})
class InterviewDeletionIntegrationTest {
    private static final String PROJECT_KEY = "x02-project-test-credential-only-012345";
    private static final String AUTH_KEY = "x04-auth-test-credential-only-012345678";
    private static final String X01_KEY = "x01-read-only-test-credential-012345678";
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired JdbcTemplate db;
    @Autowired InterviewSessions sessions;
    @Autowired TransactionTemplate tx;
    @LocalServerPort int port;

    @Test void persistsPinnedSnapshotAndScopesReadsToTheOwner() {
        UUID owner = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        var original = snapshot(7);
        var first = sessions.create(owner, project, FREE, original);
        sessions.create(owner, project, FREE, snapshot(8));

        assertEquals(original, sessions.find(owner, first.id()).orElseThrow().snapshot());
        assertEquals(7L, db.queryForObject("SELECT membership_version FROM interview.interview_session WHERE id=?",
                Long.class, first.id()));
        assertEquals(UUID.fromString(original.snapshotId()), db.queryForObject(
                "SELECT snapshot_id FROM interview.interview_session WHERE id=?", UUID.class, first.id()));
        assertTrue(sessions.find(UUID.randomUUID(), first.id()).isEmpty());
    }

    @Test void deleteCommandsAuthorizeAndBindCallerTargetOwnerAndJob() throws Exception {
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), job = UUID.randomUUID();
        var target = sessions.create(owner, project, FREE, snapshot(1));
        var sibling = sessions.create(owner, UUID.randomUUID(), FREE, snapshot(1));
        var other = sessions.create(UUID.randomUUID(), UUID.randomUUID(), FREE, snapshot(1));
        String path = projectPath(project);
        Map<String, ?> command = projectCommand(owner, project, job);

        assertDenied(call(path, null, null, command));
        assertDenied(call(path, "project", X01_KEY, command));
        assertDenied(call(path, "auth", AUTH_KEY, command));
        assertDenied(call(userPath(owner), "project", PROJECT_KEY, userCommand(owner, job)));
        assertEquals(400, call(path, "project", PROJECT_KEY, projectCommand(owner, UUID.randomUUID(), job)));
        assertEquals(409, call(path, "project", PROJECT_KEY, projectCommand(UUID.randomUUID(), project, job)));
        assertTrue(sessions.find(owner, target.id()).isPresent());
        assertEquals(204, call(path, "project", PROJECT_KEY, command));
        assertEquals(204, call(path, "project", PROJECT_KEY, command));
        assertEquals(409, call(path, "project", PROJECT_KEY, projectCommand(owner, project, UUID.randomUUID())));
        UUID untouchedProject = sibling.projectId();
        assertEquals(409, call(projectPath(untouchedProject), "project", PROJECT_KEY,
                projectCommand(owner, untouchedProject, job)));
        assertEquals(0, count(owner, project));
        assertEquals(1, count(owner, untouchedProject));
        assertEquals(1, count(other.ownerUserId(), other.projectId()));
        assertThrows(InterviewSessions.WriteBlocked.class, () -> sessions.create(owner, project, FREE, snapshot(1)));

        UUID accountJob = UUID.randomUUID();
        assertEquals(400, call(userPath(owner), "auth", AUTH_KEY, userCommand(UUID.randomUUID(), accountJob)));
        assertEquals(400, call(userPath(owner), "auth", AUTH_KEY,
                Map.of("userId", owner, "deletionJobId", accountJob, "state", "ACTIVE")));
        assertEquals(204, call(userPath(owner), "auth", AUTH_KEY, userCommand(owner, accountJob)));
        assertEquals(204, call(userPath(owner), "auth", AUTH_KEY, userCommand(owner, accountJob)));
        assertEquals(0, count(owner, untouchedProject));
        assertEquals(1, count(other.ownerUserId(), other.projectId()));
        assertThrows(InterviewSessions.WriteBlocked.class,
                () -> sessions.create(owner, UUID.randomUUID(), FREE, snapshot(1)));
    }

    @Test void deleteAnEmptyScopeLeavesADurableBarrier() throws Exception {
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), job = UUID.randomUUID();
        assertEquals(204, call(projectPath(project), "project", PROJECT_KEY, projectCommand(owner, project, job)));
        assertEquals("COMPLETED", fenceState(owner, project));
        assertThrows(InterviewSessions.WriteBlocked.class, () -> sessions.create(owner, project, FREE, snapshot(1)));
        UUID emptyOwner = UUID.randomUUID(), accountJob = UUID.randomUUID();
        assertEquals(204, call(userPath(emptyOwner), "auth", AUTH_KEY, userCommand(emptyOwner, accountJob)));
        assertEquals(204, call(userPath(emptyOwner), "auth", AUTH_KEY, userCommand(emptyOwner, accountJob)));
        assertThrows(InterviewSessions.WriteBlocked.class,
                () -> sessions.create(emptyOwner, UUID.randomUUID(), FREE, snapshot(1)));
    }

    @Test void writerThatCommitsBeforeBarrierIsIncludedInProjectAndUserCleanup() throws Exception {
        for (boolean projectOnly : new boolean[]{true, false}) {
            UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), job = UUID.randomUUID();
            CountDownLatch inserted = new CountDownLatch(1);
            CountDownLatch commit = new CountDownLatch(1);
            AtomicInteger writerPid = new AtomicInteger();
            try (var pool = Executors.newFixedThreadPool(2)) {
                var writer = pool.submit(() -> tx.execute(status -> {
                    writerPid.set(db.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    var session = sessions.create(owner, project, FREE, snapshot(1));
                    inserted.countDown();
                    await(commit);
                    return session;
                }));
                Future<?> deletion;
                try {
                    await(inserted);
                    deletion = pool.submit(() -> {
                        if (projectOnly) sessions.deleteProject(owner, project, job);
                        else sessions.deleteUser(owner, job);
                    });
                    until(() -> Boolean.TRUE.equals(db.queryForObject("""
                            SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))
                            """, Boolean.class, writerPid.get())));
                    assertFalse(deletion.isDone());
                } finally {
                    commit.countDown();
                }
                UUID sessionId = writer.get(10, TimeUnit.SECONDS).id();
                deletion.get(10, TimeUnit.SECONDS);
                assertEquals(0, count(owner, project));
                assertTrue(sessions.find(owner, sessionId).isEmpty());
                assertThrows(InterviewSessions.WriteBlocked.class, () -> sessions.create(owner, project, FREE, snapshot(1)));
            }
        }
    }

    @Test void committedBarrierHidesSessionAndRejectsAWriterWhileCleanupWaits() throws Exception {
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), job = UUID.randomUUID();
        var session = sessions.create(owner, project, FREE, snapshot(1));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var blocker = pool.submit(() -> tx.executeWithoutResult(status -> {
                db.queryForObject("SELECT id FROM interview.interview_session WHERE id=? FOR UPDATE",
                        UUID.class, session.id());
                locked.countDown();
                await(release);
            }));
            Future<Integer> deletion;
            Future<?> writer;
            try {
                await(locked);
                deletion = pool.submit(() -> call(projectPath(project), "project", PROJECT_KEY,
                        projectCommand(owner, project, job)));
                until(() -> "DELETING".equals(fenceState(owner, project)));
                assertFalse(deletion.isDone());
                assertTrue(sessions.find(owner, session.id()).isEmpty());
                writer = pool.submit(() -> sessions.create(owner, project, FREE, snapshot(1)));
            } finally {
                release.countDown();
            }
            blocker.get(10, TimeUnit.SECONDS);
            assertEquals(204, deletion.get(10, TimeUnit.SECONDS));
            var rejected = assertThrows(ExecutionException.class, () -> writer.get(10, TimeUnit.SECONDS));
            assertInstanceOf(InterviewSessions.WriteBlocked.class, rejected.getCause());
            assertEquals(0, count(owner, project));
        }
    }

    @Test void projectAndUserDeletesCanCompleteConcurrentlyWithoutReopeningEitherScope() throws Exception {
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID();
        sessions.create(owner, project, FREE, snapshot(1));
        sessions.create(owner, UUID.randomUUID(), FREE, snapshot(1));
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var projectDelete = pool.submit(() -> { await(start); sessions.deleteProject(owner, project, UUID.randomUUID()); });
            var userDelete = pool.submit(() -> { await(start); sessions.deleteUser(owner, UUID.randomUUID()); });
            start.countDown();
            projectDelete.get(10, TimeUnit.SECONDS);
            userDelete.get(10, TimeUnit.SECONDS);
        }
        assertEquals("COMPLETED", fenceState(owner, project));
        assertEquals("COMPLETED", db.queryForObject("""
                SELECT state FROM interview.interview_write_fence WHERE scope='USER' AND owner_user_id=?
                """, String.class, owner));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM interview.interview_session WHERE owner_user_id=?",
                Integer.class, owner));
    }

    @Test void failedCleanupKeepsBarrierAndSameJobCanRetry() throws Exception {
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), job = UUID.randomUUID();
        var session = sessions.create(owner, project, FREE, snapshot(1));
        String blocker = "interview.test_block_" + UUID.randomUUID().toString().replace("-", "");
        db.execute("CREATE TABLE " + blocker + " (id uuid REFERENCES interview.interview_session(id))");
        try {
            db.update("INSERT INTO " + blocker + " VALUES (?)", session.id());
            assertEquals(503, call(projectPath(project), "project", PROJECT_KEY, projectCommand(owner, project, job)));
            assertEquals("DELETING", fenceState(owner, project));
            assertEquals(1, count(owner, project));
            assertTrue(sessions.find(owner, session.id()).isEmpty());
            assertThrows(InterviewSessions.WriteBlocked.class, () -> sessions.create(owner, project, FREE, snapshot(1)));
        } finally {
            db.execute("DROP TABLE " + blocker);
        }
        assertEquals(204, call(projectPath(project), "project", PROJECT_KEY, projectCommand(owner, project, job)));
        assertEquals("COMPLETED", fenceState(owner, project));
        assertEquals(0, count(owner, project));
        assertEquals(204, call(projectPath(project), "project", PROJECT_KEY, projectCommand(owner, project, job)));
    }

    private int call(String path, String caller, String key, Map<String, ?> command) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(command)));
        if (caller != null) request.header("X-DevField-Service", caller);
        if (key != null) request.header("X-DevField-Service-Key", key);
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 204) assertTrue(response.body().isEmpty());
        return response.statusCode();
    }

    private static String projectPath(UUID project) { return "/internal/projects/" + project + "/interviews"; }
    private static String userPath(UUID user) { return "/internal/users/" + user + "/data"; }

    private static Map<String, ?> projectCommand(UUID owner, UUID project, UUID job) {
        return Map.of("ownerUserId", owner, "projectId", project, "deletionJobId", job);
    }

    private static Map<String, ?> userCommand(UUID owner, UUID job) {
        return Map.of("userId", owner, "deletionJobId", job, "state", "DELETING");
    }

    private static void assertDenied(int status) { assertTrue(status == 401 || status == 403); }

    private int count(UUID owner, UUID project) {
        return db.queryForObject("SELECT count(*) FROM interview.interview_session WHERE owner_user_id=? AND project_id=?",
                Integer.class, owner, project);
    }

    private String fenceState(UUID owner, UUID project) {
        return db.queryForObject("""
                SELECT state FROM interview.interview_write_fence
                WHERE scope='PROJECT' AND owner_user_id=? AND project_id=?
                """, String.class, owner, project);
    }

    private static ProjectSnapshot snapshot(long version) {
        String member = UUID.randomUUID().toString(), repository = UUID.randomUUID().toString();
        String evidence = UUID.randomUUID().toString(), sha = "a".repeat(40);
        return new ProjectSnapshot(UUID.randomUUID().toString(), version,
                List.of(new ProjectSnapshot.Member(member, repository, "42", sha)),
                List.of(new ProjectSnapshot.Topic("JAVA", evidence)), List.of(),
                List.of(new ProjectSnapshot.Evidence(evidence, member, repository, "42", sha,
                        "src/Main.java", 1, 1, "CODE_FACT", "Declares a Java class.")));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "transaction did not reach its checkpoint");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void until(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "no PostgreSQL lock wait was observed");
            Thread.sleep(20);
        }
    }
}
