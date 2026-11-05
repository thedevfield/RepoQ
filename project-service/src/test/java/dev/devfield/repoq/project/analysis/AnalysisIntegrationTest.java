package dev.devfield.repoq.project.analysis;

import static org.junit.jupiter.api.Assertions.*;
import static dev.devfield.repoq.project.analysis.AnalysisSnapshots.*;

import dev.devfield.repoq.project.api.ApiProblem;
import dev.devfield.repoq.project.data.Projects;
import dev.devfield.repoq.project.github.GitHubRepositoryClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@TestPropertySource(properties = {
        "devfield.project.auth.issuer=test-issuer", "devfield.project.auth.audience=test-audience",
        "devfield.project.auth.jwks-uri=http://127.0.0.1:1/api/auth/jwks",
        "devfield.project.auth.session-check-uri=http://127.0.0.1:1/internal/auth/session-check",
        "devfield.project.auth.service-key=test-service-key"
})
class AnalysisIntegrationTest {
    private static final String SHA = "a".repeat(40);
    @Autowired JdbcTemplate db;
    @Autowired Projects projects;
    @Autowired AnalysisJobs jobs;
    @Autowired AnalysisSnapshots productionSnapshots;
    @Autowired AnalysisSettings settings;
    @Autowired TransactionTemplate tx;
    private final List<UUID> owners = new ArrayList<>();
    record Fixture(UUID owner, UUID project, UUID job, List<Projects.Member> members) {}

    @AfterEach
    void cleanup() {
        for (UUID owner : owners) {
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
    }

    @Test
    void tc01And02AtomicConcurrentClaim() throws Exception {
        Fixture f = fixture(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<AnalysisJobs.Execution> claim = () -> { start.await(); return jobs.claim(f.job()); };
            var a = pool.submit(claim); var b = pool.submit(claim); start.countDown();
            var results = Arrays.asList(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(Objects::nonNull).count());
            assertEquals(1, number("SELECT attempt_count FROM project.analysis_job WHERE id=?", f.job()));
            assertEquals("RUNNING", state(f));
            assertNull(jobs.claim(f.job()));
        }
    }

    @Test
    void tc03UserFencePrecedesProjectAndJobLocks() {
        Fixture f = fixture(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            AtomicInteger pid = new AtomicInteger();
            Future<?>[] worker = new Future<?>[1];
            tx.executeWithoutResult(s -> {
                db.queryForObject("SELECT id FROM project.project_write_fence WHERE owner_user_id=? AND scope='USER' FOR UPDATE", UUID.class, f.owner());
                worker[0] = pool.submit(() -> tx.execute(status -> {
                    pid.set(db.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    return jobs.claim(f.job());
                }));
                awaitLock(pid);
                assertDoesNotThrow(() -> db.queryForObject("SELECT id FROM project.project_write_fence WHERE project_id=? FOR UPDATE NOWAIT", UUID.class, f.project()));
                assertDoesNotThrow(() -> db.queryForObject("SELECT id FROM project.registered_project WHERE id=? FOR UPDATE NOWAIT", UUID.class, f.project()));
                assertDoesNotThrow(() -> db.queryForObject("SELECT id FROM project.analysis_job WHERE id=? FOR UPDATE NOWAIT", UUID.class, f.job()));
            });
            assertDoesNotThrow(() -> worker[0].get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void tc04To06RestartRecoveryPinsShaAndRejectsOldGeneration() {
        Fixture f = fixture(1);
        var first = jobs.claim(f.job());
        jobs.progress(first, new AnalysisJobs.Progress(f.members().getFirst().id(), "RUNNING", SHA, null));
        expire(f);
        assertThrows(IllegalStateException.class, () -> jobs.heartbeat(first));
        assertTrue(jobs.expired(100).contains(f.job()));
        assertTrue(jobs.recover(f.job()));
        assertFalse(jobs.recover(f.job()));
        assertEquals("QUEUED", state(f));
        var second = jobs.claim(f.job());
        assertEquals(2, second.attempt());
        assertThrows(IllegalStateException.class, () -> jobs.progress(first, new AnalysisJobs.Progress(f.members().getFirst().id(), "READY", SHA, null)));
        assertThrows(IllegalStateException.class, () -> jobs.retry(first, "network_error", 0));
        assertThrows(IllegalArgumentException.class, () -> jobs.progress(second, new AnalysisJobs.Progress(f.members().getFirst().id(), "READY", "b".repeat(40), null)));
        expire(f); jobs.recover(f.job());
        var third = jobs.claim(f.job()); assertEquals(settings.maxAttempts(), third.attempt());
        expire(f); jobs.recover(f.job());
        assertEquals("FAILED", state(f)); assertNull(jobs.claim(f.job()));
        assertEquals("attempts_exhausted", db.queryForObject("SELECT failure_code FROM project.analysis_job WHERE id=?", String.class, f.job()));
    }

    @Test
    void retryDueTimeAndConfigAreEnforced() {
        Fixture f = fixture(1);
        var run = jobs.claim(f.job());
        jobs.retry(run, "github_rate_limited", 60);
        assertNull(jobs.claim(f.job()));
        assertFalse(jobs.queued(100).contains(f.job()));
        db.update("UPDATE project.analysis_job SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?", f.job());
        assertNotNull(jobs.claim(f.job()));
        assertThrows(IllegalArgumentException.class, () -> new AnalysisSettings(false, 0, 60, 300));
    }

    @Test
    void tc07CancelledExecutionRejectsEveryWrite() {
        Fixture f = fixture(1); var run = jobs.claim(f.job()); Draft draft = ready(f, run);
        db.update("UPDATE project.analysis_job SET state='CANCELLED' WHERE id=?", f.job());
        assertThrows(IllegalStateException.class, () -> jobs.heartbeat(run));
        assertThrows(IllegalStateException.class, () -> jobs.progress(run, new AnalysisJobs.Progress(f.members().getFirst().id(), "READY", SHA, null)));
        assertThrows(IllegalStateException.class, () -> jobs.finish(run, "FAILED", "network_error"));
        assertThrows(IllegalStateException.class, () -> jobs.retry(run, "network_error", 0));
        assertThrows(IllegalStateException.class, () -> verified().publish(run, draft));
        expire(f); assertFalse(jobs.recover(f.job())); assertNull(jobs.claim(f.job()));
        assertEquals("CANCELLED", state(f)); assertEquals(0, snapshots(f));
    }

    @Test
    void tc08MembershipAdditionAndRemovalRaceClaim() throws Exception {
        for (boolean add : List.of(true, false)) {
            Fixture f = fixture(2);
            try (var pool = Executors.newFixedThreadPool(2)) {
                CountDownLatch start = new CountDownLatch(1);
                var claim = pool.submit(() -> { start.await(); return jobs.claim(f.job()); });
                var mutation = pool.submit(() -> {
                    start.await();
                    try {
                        if (add) projects.add(f.owner(), f.project(), List.of(repository()), null, null);
                        else projects.remove(f.owner(), f.project(), f.members().getLast().id());
                        return true;
                    } catch (ApiProblem e) { assertEquals("analysis_in_progress", e.code()); return false; }
                });
                start.countDown();
                var run = claim.get(10, TimeUnit.SECONDS); boolean changed = mutation.get(10, TimeUnit.SECONDS);
                if (changed) { assertNull(run); assertEquals("CANCELLED", state(f)); }
                else { assertNotNull(run); assertEquals("RUNNING", state(f)); }
                assertEquals(1, number("SELECT count(*) FROM project.analysis_job WHERE project_id=? AND state IN ('RUNNING','QUEUED')", f.project()));
            }
        }
    }

    @Test
    void tc09MembershipAndFenceChangesRejectPublication() {
        Fixture f = fixture(1); var run = jobs.claim(f.job()); Draft draft = ready(f, run);
        db.update("UPDATE project.registered_project SET membership_version=membership_version+1 WHERE id=?", f.project());
        assertThrows(IllegalStateException.class, () -> verified().publish(run, draft));
        assertThrows(IllegalStateException.class, () -> jobs.heartbeat(run));
        assertEquals(0, snapshots(f));
        db.update("UPDATE project.project_write_fence SET state='DELETING',deletion_job_id=? WHERE owner_user_id=? AND scope='USER'", UUID.randomUUID(), f.owner());
        assertThrows(ApiProblem.class, () -> jobs.finish(run, "FAILED", "cancelled"));
    }

    @Test
    void tc10And11AtomicPublicationAndDuplicateRejection() {
        Fixture f = fixture(2); var run = jobs.claim(f.job()); Draft draft = ready(f, run);
        UUID snapshot = verified().publish(run, draft);
        assertEquals("READY", state(f)); assertEquals(1, snapshots(f));
        assertEquals(snapshot, db.queryForObject("SELECT latest_ready_snapshot_id FROM project.registered_project WHERE id=?", UUID.class, f.project()));
        assertEquals(2, number("SELECT count(*) FROM project.snapshot_member WHERE snapshot_id=?", snapshot));
        assertThrows(IllegalStateException.class, () -> verified().publish(run, draft));
        assertEquals(1, snapshots(f));
        assertThrows(DataAccessException.class, () -> db.update("UPDATE project.analysis_snapshot SET coverage='[]'::jsonb WHERE id=?", snapshot));
        assertThrows(DataAccessException.class, () -> db.update("UPDATE project.evidence_reference SET summary='changed' WHERE snapshot_id=?", snapshot));
        assertEquals(1, db.update("UPDATE project.analysis_snapshot SET access_state='BLOCKED' WHERE id=?", snapshot));
    }

    @Test
    void tc12CrossProjectAndCrossSnapshotReferencesRejectedByApplicationAndDatabase() {
        Fixture a = fixture(1), b = fixture(1);
        var ar = jobs.claim(a.job()); var br = jobs.claim(b.job());
        Draft ad = ready(a, ar), bd = ready(b, br);
        assertThrows(IllegalArgumentException.class, () -> verified().publish(ar, bd));
        UUID as = verified().publish(ar, ad), bs = verified().publish(br, bd);
        assertThrows(DataAccessException.class, () -> db.update("UPDATE project.registered_project SET latest_ready_snapshot_id=? WHERE id=?", as, b.project()));
        assertThrows(DataAccessException.class, () -> db.update("INSERT INTO project.snapshot_member VALUES (?,?,?,?,?,?, 'PUBLIC')",
                UUID.randomUUID(), as, a.project(), b.members().getFirst().id(), b.members().getFirst().githubRepositoryId(), SHA));
        assertThrows(DataAccessException.class, () -> db.update("INSERT INTO project.available_topic VALUES (?,?,?,true,?)",
                UUID.randomUUID(), bs, "FOREIGN", ad.evidence().getFirst().id()));
        assertThrows(DataAccessException.class, () -> db.update("INSERT INTO project.evidence_reference VALUES (?,?,?,?,?,?,1,2,'CODE_FACT','safe')",
                UUID.randomUUID(), bs, ad.members().getFirst().id(), ad.members().getFirst().githubRepositoryId(), SHA, "src/A.java"));
        assertThrows(DataAccessException.class, () -> db.update("INSERT INTO project.analysis_snapshot " +
                "(id,project_id,analysis_job_id,membership_version,analysis_version,roles,relations,coverage) " +
                "VALUES (?,?,?,1,'v1','[]','[]','[]')", UUID.randomUUID(), b.project(), a.job()));
    }

    @Test
    void tc13MalformedShaPathAndLinesRejected() {
        Fixture f = fixture(1); var run = jobs.claim(f.job()); Draft d = ready(f, run);
        Member m = d.members().getFirst(); Evidence e = d.evidence().getFirst();
        assertThrows(IllegalArgumentException.class, () -> verified().publish(run,
                new Draft(List.of(new Member(m.id(), m.repositoryId(), m.githubRepositoryId(), "short")), d.evidence(), d.topics(), d.roles(), d.relations(), d.coverage())));
        for (String path : List.of("../secret", "/etc/passwd", "a//b", "a\\b", "a/./b", "a\nb", "C:/secret", " ")) {
            Evidence bad = new Evidence(e.id(), e.memberId(), path, 1, 2, e.claimType(), e.summary());
            assertThrows(IllegalArgumentException.class, () -> verified().publish(run, evidence(d, bad)));
        }
        for (Evidence bad : List.of(new Evidence(e.id(), e.memberId(), e.path(), 0, 1, e.claimType(), e.summary()),
                new Evidence(e.id(), e.memberId(), e.path(), 3, 2, e.claimType(), e.summary()),
                new Evidence(e.id(), e.memberId(), e.path(), 1, null, e.claimType(), e.summary())))
            assertThrows(IllegalArgumentException.class, () -> verified().publish(run, evidence(d, bad)));
        assertEquals(0, snapshots(f));
    }

    @Test
    void tc14IncompleteProgressCoverageTopicsAndRelationsRejectReady() {
        Fixture f = fixture(2); var run = jobs.claim(f.job()); Draft d = draft(f);
        assertThrows(IllegalArgumentException.class, () -> verified().publish(run, d));
        ready(f, run);
        assertThrows(IllegalArgumentException.class, () -> verified().publish(run,
                new Draft(d.members(), d.evidence(), d.topics(), List.of(), List.of(), List.of())));
        assertThrows(IllegalArgumentException.class, () -> verified().publish(run,
                new Draft(d.members(), d.evidence(), List.of(), List.of(), List.of(), d.coverage())));
        var relation = new Relation(d.members().get(0).id(), d.members().get(1).id(), ClaimType.CODE_FACT,
                List.of(d.evidence().getFirst().id()), "One-sided relation");
        assertThrows(IllegalArgumentException.class, () -> verified().publish(run,
                new Draft(d.members(), d.evidence(), d.topics(), List.of(), List.of(relation), d.coverage())));
        assertEquals("RUNNING", state(f)); assertEquals(0, snapshots(f));
    }

    @Test
    void tc15PrivateAndMixedJobsFailWithoutPublication() {
        for (int count : List.of(1, 2)) {
            Fixture f = fixture(count); makePrivate(f);
            assertNull(jobs.claim(f.job())); assertEquals("FAILED", state(f));
            assertEquals("private_analysis_not_supported", db.queryForObject("SELECT failure_code FROM project.analysis_job WHERE id=?", String.class, f.job()));
            assertEquals(0, snapshots(f));
        }
        Fixture f = fixture(1); var run = jobs.claim(f.job()); Draft d = ready(f, run); makePrivate(f);
        assertThrows(IllegalStateException.class, () -> verified().publish(run, d));
        assertEquals(0, snapshots(f));
    }

    @Test
    void tc16And20PreviousSnapshotPreservedAndMidInsertFailureRollsBack() {
        Fixture first = fixture(1); var run = jobs.claim(first.job()); Draft original = ready(first, run);
        UUID snapshot = verified().publish(run, original);
        projects.add(first.owner(), first.project(), List.of(repository()), null, null);
        UUID next = db.queryForObject("SELECT id FROM project.analysis_job WHERE project_id=? AND state='QUEUED'", UUID.class, first.project());
        var nextRun = jobs.claim(next);
        var rows = db.query("SELECT id,github_repository_id FROM project.project_repository WHERE project_id=? AND removed_at IS NULL ORDER BY id",
                (rs,n) -> new Projects.Member(rs.getObject(1, UUID.class), rs.getString(2), "owner/repo", "PUBLIC", "ACTIVE", "QUEUED", null), first.project());
        Fixture second = new Fixture(first.owner(), first.project(), next, rows);
        Draft d = ready(second, nextRun); Evidence e = d.evidence().getFirst();
        Draft collision = evidence(d, new Evidence(original.evidence().getFirst().id(), e.memberId(), e.path(), 1, 2, e.claimType(), e.summary()));
        // Point the topic at the colliding ID so validation succeeds and the database fails after member inserts.
        collision = new Draft(collision.members(), collision.evidence(), List.of(new Topic("API_BACKEND", true, original.evidence().getFirst().id())), List.of(), List.of(), collision.coverage());
        Draft input = collision;
        assertThrows(DataAccessException.class, () -> verified().publish(nextRun, input));
        assertEquals(1, snapshots(first)); assertEquals("RUNNING", state(second));
        assertEquals(snapshot, db.queryForObject("SELECT latest_ready_snapshot_id FROM project.registered_project WHERE id=?", UUID.class, first.project()));
        assertEquals(1, number("SELECT count(*) FROM project.snapshot_member WHERE project_id=?", first.project()));
        jobs.finish(nextRun, "FAILED", "analysis_failed"); assertEquals(1, snapshots(first));
    }

    @Test
    void tc19NoAutomaticExecutionOrProductionVerifier() {
        Fixture f = fixture(1);
        assertFalse(settings.enabled()); assertEquals("QUEUED", state(f));
        assertTrue(new AnalysisSettings(true, 3, 60, 300).enabled());
        var run = jobs.claim(f.job()); Draft d = ready(f, run);
        assertThrows(IllegalStateException.class, () -> productionSnapshots.publish(run, d));
        var denied = new AnalysisSnapshots(db, jobs, tx, Optional.of(sources -> null));
        assertThrows(IllegalStateException.class, () -> denied.publish(run, d));
        assertThrows(IllegalStateException.class, () -> tx.execute(s -> verified().publish(run, d)));
        assertEquals(0, snapshots(f));
    }

    @Test
    void accessVerificationRaceAndLockWaitCannotReviveLease() {
        Fixture f = fixture(1); var run = jobs.claim(f.job()); Draft d = ready(f, run);
        var verifier = new AnalysisSnapshots(db, jobs, tx, Optional.of(sources -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            db.update("UPDATE project.analysis_job SET state='CANCELLED' WHERE id=?", f.job());
            return new PublicAccessVerifier.Proof(Set.copyOf(sources), java.time.Instant.now());
        }));
        assertThrows(IllegalStateException.class, () -> verifier.publish(run, d)); assertEquals(0, snapshots(f));
    }

    @Test
    void wallClockAfterFenceWaitAndHardDeadlineRejectRenewal() throws Exception {
        Fixture f = fixture(1); var run = jobs.claim(f.job());
        try (var pool = Executors.newSingleThreadExecutor()) {
            AtomicInteger pid = new AtomicInteger(); Future<?>[] waiter = new Future<?>[1];
            tx.executeWithoutResult(s -> {
                db.queryForObject("SELECT id FROM project.project_write_fence WHERE owner_user_id=? AND scope='USER' FOR UPDATE", UUID.class, f.owner());
                waiter[0] = pool.submit(() -> tx.execute(status -> {
                    pid.set(db.queryForObject("SELECT pg_backend_pid()", Integer.class)); jobs.heartbeat(run); return null;
                }));
                awaitLock(pid);
                expire(f);
            });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> waiter[0].get(10, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
        }
        jobs.recover(f.job()); var next = jobs.claim(f.job());
        db.update("UPDATE project.analysis_job SET started_at=clock_timestamp()-interval '2 hours'," +
                "lease_expires_at=clock_timestamp()+interval '1 minute' WHERE id=?", f.job());
        assertThrows(IllegalStateException.class, () -> jobs.heartbeat(next));
        assertTrue(jobs.expired(100).contains(f.job())); assertTrue(jobs.recover(f.job()));
        assertEquals("QUEUED", state(f));
    }

    @Test
    void databaseForeignKeysAndChecksRejectInvalidDraftRowsBeforeSealing() {
        Fixture a = fixture(1), b = fixture(1);
        jobs.claim(a.job()); jobs.claim(b.job());
        UUID sa = insertEnvelope(a), sb = insertEnvelope(b), ma = UUID.randomUUID(), ea = UUID.randomUUID();
        String insertMember = "INSERT INTO project.snapshot_member VALUES (?,?,?,?,?,?,'PUBLIC')";
        var am = a.members().getFirst(); var bm = b.members().getFirst();
        db.update(insertMember, ma, sa, a.project(), am.id(), am.githubRepositoryId(), SHA);
        sqlState("23503", () -> db.update(insertMember, UUID.randomUUID(), sa, a.project(), bm.id(), bm.githubRepositoryId(), SHA));
        sqlState("23503", () -> db.update(insertMember, UUID.randomUUID(), sa, b.project(), bm.id(), bm.githubRepositoryId(), SHA));
        sqlState("23514", () -> db.update(insertMember, UUID.randomUUID(), sb, b.project(), bm.id(), bm.githubRepositoryId(), "bad"));
        sqlState("23505", () -> db.update(insertMember, UUID.randomUUID(), sa, a.project(), am.id(), am.githubRepositoryId(), SHA));
        String insertEvidence = "INSERT INTO project.evidence_reference VALUES (?,?,?,?,?,?,?,?, 'CODE_FACT','Safe summary')";
        db.update(insertEvidence, ea, sa, ma, am.githubRepositoryId(), SHA, "src/App.java", 1, 2);
        sqlState("23503", () -> db.update(insertEvidence, UUID.randomUUID(), sb, ma, am.githubRepositoryId(), SHA, "src/App.java", 1, 2));
        sqlState("23503", () -> db.update(insertEvidence, UUID.randomUUID(), sa, ma, am.githubRepositoryId(), "b".repeat(40), "src/App.java", 1, 2));
        for (String path : List.of("../x", "/x", "x\\y", "x//y", "x/./y", "x/", "x\ny", " "))
            sqlState("23514", () -> db.update(insertEvidence, UUID.randomUUID(), sa, ma, am.githubRepositoryId(), SHA, path, 1, 2));
        sqlState("23514", () -> db.update(insertEvidence, UUID.randomUUID(), sa, ma, am.githubRepositoryId(), SHA, "x", 2, 1));
        sqlState("23514", () -> db.update(insertEvidence, UUID.randomUUID(), sa, ma, am.githubRepositoryId(), SHA, "x", 1, null));
        sqlState("23503", () -> db.update("INSERT INTO project.available_topic VALUES (?,?,?,true,?)", UUID.randomUUID(), sb, "FOREIGN", ea));
        sqlState("23514", () -> db.update("INSERT INTO project.available_topic VALUES (?,?,?,true,NULL)", UUID.randomUUID(), sb, "EMPTY"));
        // A fresh job avoids a UNIQUE failure masking the cross-project Job FK.
        Fixture c = fixture(1); jobs.claim(c.job());
        sqlState("23503", () -> db.update("INSERT INTO project.analysis_snapshot " +
                "(id,project_id,analysis_job_id,membership_version,analysis_version,roles,relations,coverage) " +
                "VALUES (?,?,?,1,'v1','[]','[]','[]')", UUID.randomUUID(), a.project(), c.job()));
    }

    private UUID insertEnvelope(Fixture f) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO project.analysis_snapshot(id,project_id,analysis_job_id,membership_version,analysis_version,roles,relations,coverage) " +
                "VALUES (?,?,?,1,'v1','[]','[]','[]')", id, f.project(), f.job());
        return id;
    }
    private void sqlState(String expected, Runnable action) {
        DataAccessException error = assertThrows(DataAccessException.class, action::run);
        assertEquals(expected, assertInstanceOf(java.sql.SQLException.class, error.getMostSpecificCause()).getSQLState());
    }

    private AnalysisSnapshots verified() {
        return new AnalysisSnapshots(db, jobs, tx, Optional.of(sources -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return new PublicAccessVerifier.Proof(Set.copyOf(sources), java.time.Instant.now());
        }));
    }
    private Fixture fixture(int count) {
        UUID owner = UUID.randomUUID(); owners.add(owner);
        List<GitHubRepositoryClient.Repository> repositories = new ArrayList<>();
        for (int i = 0; i < count; i++) repositories.add(repository());
        var result = projects.register(owner, repositories, "Analysis fixture", null, null);
        UUID job = db.queryForObject("SELECT id FROM project.analysis_job WHERE project_id=?", UUID.class, result.id());
        return new Fixture(owner, result.id(), job, result.repositories());
    }
    private static GitHubRepositoryClient.Repository repository() {
        String id = Long.toString(UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
        return new GitHubRepositoryClient.Repository(id, "owner/repo" + id, "repo" + id);
    }
    private Draft draft(Fixture f) {
        List<Member> members = f.members().stream().map(m -> new Member(UUID.randomUUID(), m.id(), m.githubRepositoryId(), SHA)).toList();
        var evidence = members.stream().map(m -> new Evidence(UUID.randomUUID(), m.id(), "src/App.java", 1, 2, ClaimType.CODE_FACT, "Declares an API handler")).toList();
        return new Draft(members, evidence, List.of(new Topic("API_BACKEND", true, evidence.getFirst().id())), List.of(), List.of(),
                members.stream().map(m -> new Coverage(m.id(), 1, 0)).toList());
    }
    private Draft ready(Fixture f, AnalysisJobs.Execution run) {
        for (var m : f.members()) jobs.progress(run, new AnalysisJobs.Progress(m.id(), "READY", SHA, null));
        return draft(f);
    }
    private Draft evidence(Draft d, Evidence replacement) {
        List<Evidence> evidence = new ArrayList<>(d.evidence()); evidence.set(0, replacement);
        return new Draft(d.members(), evidence, d.topics(), d.roles(), d.relations(), d.coverage());
    }
    private void makePrivate(Fixture f) {
        UUID connection = UUID.randomUUID();
        db.update("INSERT INTO project.github_connection(id,devfield_user_id) VALUES (?,?)", connection, f.owner());
        db.update("UPDATE project.project_repository SET visibility='PRIVATE',github_connection_id=? WHERE id=?", connection, f.members().getFirst().id());
    }
    private void expire(Fixture f) { db.update("UPDATE project.analysis_job SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?", f.job()); }
    private String state(Fixture f) { return db.queryForObject("SELECT state FROM project.analysis_job WHERE id=?", String.class, f.job()); }
    private int snapshots(Fixture f) { return number("SELECT count(*) FROM project.analysis_snapshot WHERE project_id=?", f.project()); }
    private int number(String sql, UUID id) { return db.queryForObject(sql, Integer.class, id); }
    private void awaitLock(AtomicInteger pid) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (pid.get() == 0 || !Boolean.TRUE.equals(db.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock')", Boolean.class, pid.get())))
                Thread.sleep(10);
        });
    }
}
