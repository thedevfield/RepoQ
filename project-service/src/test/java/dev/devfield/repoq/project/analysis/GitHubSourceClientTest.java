package dev.devfield.repoq.project.analysis;

import static org.junit.jupiter.api.Assertions.*;
import static dev.devfield.repoq.project.github.GitHubSourceClient.*;
import dev.devfield.repoq.project.github.GitHubSourceClient;
import dev.devfield.repoq.project.github.GitHubSourceClient.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

class GitHubSourceClientTest {
    MockSourceServer server;
    @BeforeEach void start() throws Exception { server = new MockSourceServer(); }
    @AfterEach void stop() { server.close(); }
    AnalysisJobs.Target target(String sha) { return new AnalysisJobs.Target(UUID.randomUUID(), "42", sha); }
    Budget budget() { return new Budget(140, () -> {}); }

    @Test void pinnedCommitRenameAndAnonymousIdentity() {
        var repo = server.add("42"); repo.name = "renamed/new-name";
        var client = server.client(); var target = target(null); AtomicReference<String> pinned = new AtomicReference<>();
        Plan plan = client.prepare(target, 40, TOTAL_BYTES, budget(), pinned::set);
        repo.head = "f".repeat(40);
        var result = client.collect(plan, budget());
        assertEquals(MockSourceServer.SHA, result.commitSha()); assertEquals(pinned.get(), result.commitSha());
        assertEquals(1, result.files().size()); assertEquals(0, server.authorizationHeaders.get());
        assertTrue(server.paths.stream().anyMatch(p -> p.startsWith("/repos/renamed/new-name/")));
        server.paths.clear();
        client.prepare(target(MockSourceServer.SHA), 40, TOTAL_BYTES, budget(), sha -> {});
        assertTrue(server.paths.stream().noneMatch(p -> p.contains("/git/ref/")));
        repo.returnedId = "43";
        assertEquals("repository_id_mismatch", assertThrows(Failure.class, () -> client.prepare(target(null), 40, TOTAL_BYTES, budget(), sha -> {})).code());
    }

    @Test void decodedSizeCannotExceedDurableReservation() {
        server.add("42"); var client=server.client(); var budget=budget();
        var plan=client.prepare(target(null),40,TOTAL_BYTES,budget,sha->{});
        var access=client.openCollection(plan,budget); var file=plan.files().getFirst();
        var tooSmall=new FileEntry(file.path(),file.sha(),file.size()-1);
        var reservations=new java.util.concurrent.atomic.AtomicInteger();
        assertEquals("invalid_github_response",assertThrows(Failure.class,()->client.download(access,tooSmall,budget,reservations::incrementAndGet)).code());
        assertEquals(1,reservations.get());
    }

    @Test void impossibleWorkerRequestLimitsAreRejectedAtConfiguration() {
        assertThrows(IllegalArgumentException.class,()->new AnalysisSettings(false,3,60,300,19));
        assertThrows(IllegalArgumentException.class,()->new AnalysisSettings(false,3,60,300,61));
    }

    @Test void expiredCollectionProofCannotSpendReservedVerificationCalls() {
        var repo=server.add("42");var client=server.client();var plan=client.prepare(target(null),40,TOTAL_BYTES,budget(),sha->{});
        var access=client.openCollection(plan,budget());
        var stale=new Access(access.fullName(),new PublicAccessVerifier.Proof(access.proof().sources(),Instant.now().minusSeconds(31)));
        var limited=new Budget(3,()->{});client.fitPlans(List.of(plan),limited,1);client.openCollection(plan,limited);
        int calls=server.paths.size();
        assertEquals("analysis_window_complete",assertThrows(Failure.class,()->client.refreshCollection(plan,stale,limited)).code());
        assertEquals(calls,server.paths.size());
        repo.privateRepo=true;
        assertEquals("repository_not_public",assertThrows(Failure.class,()->client.refreshCollection(plan,stale,budget())).code());
        assertEquals(0,server.contentRequests.get());
    }

    @Test void privateTransitionBlocksCollectionAndPublicationProof() {
        var repo = server.add("42"); var client = server.client(); var target = target(null);
        Plan plan = client.prepare(target, 40, TOTAL_BYTES, budget(), sha -> {});
        repo.privateRepo = true;
        assertEquals("repository_not_public", assertThrows(Failure.class, () -> client.collect(plan, budget())).code());
        assertEquals(0, server.contentRequests.get());
        assertThrows(Failure.class, () -> client.verify(List.of(new PublicAccessVerifier.Source(target.repositoryId(), "42", MockSourceServer.SHA))));
    }

    @Test void treeAndObjectIntegrityFailures() {
        var repo = server.add("42"); var client = server.client();
        repo.truncated = true;
        assertEquals("tree_truncated", assertThrows(Failure.class, () -> client.prepare(target(null), 40, TOTAL_BYTES, budget(), sha -> {})).code());
        repo.truncated = false; repo.badCommitSha = true;
        assertEquals("commit_sha_mismatch", assertThrows(Failure.class, () -> client.prepare(target(null), 40, TOTAL_BYTES, budget(), sha -> {})).code());
        repo.badCommitSha = false; repo.badBlobSha = true;
        Plan plan = client.prepare(target(null), 40, TOTAL_BYTES, budget(), sha -> {});
        assertEquals("invalid_github_response", assertThrows(Failure.class, () -> client.collect(plan, budget())).code());
    }

    @Test void fileSelectionAndDecodedByteQuotas() {
        var repo = server.add("42");
        repo.files.put("src/Huge.java", "x".repeat(FILE_BYTES + 1));
        repo.files.put(".env", "SECRET_VALUE");
        repo.files.put("node_modules/Bad.java", "bad");
        repo.files.put("target/Bad.java", "bad");
        repo.files.put("vendor/Bad.java", "bad");
        repo.files.put("src/Binary.java", "\0binary");
        repo.files.put("src/Lfs.java", "version https://git-lfs.github.com/spec/v1\noid sha256:whatever");
        for (int i = 0; i < 45; i++) repo.files.put("src/File" + i + ".java", "class File" + i + "{}");
        var client = server.client(); Plan plan = client.prepare(target(null), 10, 500, budget(), sha -> {});
        assertTrue(plan.files().size() <= 10); assertTrue(plan.files().stream().mapToInt(FileEntry::size).sum() <= 500);
        assertTrue(plan.exclusions().containsKey("file_size")); assertTrue(plan.exclusions().containsKey("credential_path"));
        assertTrue(plan.exclusions().containsKey("generated_or_vendor"));
        var result = client.collect(plan, budget());
        assertTrue(result.files().stream().noneMatch(f -> f.text().contains("\0") || f.text().startsWith("version https://git-lfs")));
        assertTrue(server.paths.stream().noneMatch(p -> p.contains("SECRET_VALUE")));
    }

    @Test void symlinkAndSubmoduleNeverRequested() {
        var repo = server.add("42"); repo.mode = "120000";
        var client = server.client(); var plan = client.prepare(target(null), 40, TOTAL_BYTES, budget(), sha -> {});
        assertTrue(plan.files().isEmpty()); assertEquals(1, plan.exclusions().get("non_regular"));
        client.collect(plan, budget()); assertEquals(0, server.contentRequests.get());
        repo.mode = "160000";
        assertTrue(client.prepare(target(null), 40, TOTAL_BYTES, budget(), sha -> {}).files().isEmpty());
    }

    @Test void manyJavaFilesDoNotDisplaceBuildAndReadme() {
        var repo = server.add("42");
        for (int i = 0; i < 60; i++) repo.files.put("src/File" + i + ".java", "class Example {}");
        repo.files.put("pom.xml", "<project/>");
        repo.files.put("README.md", "Spring application");
        Plan plan = server.client().prepare(target(null), 40, TOTAL_BYTES, budget(), sha -> {});
        assertEquals(40, plan.files().size());
        assertTrue(plan.files().stream().anyMatch(f -> f.path().equals("pom.xml")));
        assertTrue(plan.files().stream().anyMatch(f -> f.path().equals("README.md")));
    }

    @Test void knownRemoteRateLimitTakesPrecedenceOverExhaustedLocalWindow() {
        server.add("42");server.headers.put("X-RateLimit-Remaining","0");
        server.headers.put("X-RateLimit-Reset",Long.toString(Instant.now().plusSeconds(120).getEpochSecond()));
        var failure=assertThrows(Failure.class,()->server.client().prepare(target(null),40,TOTAL_BYTES,new Budget(1,()->{}),sha->{}));
        assertEquals("github_rate_limited",failure.code());assertTrue(failure.retrySeconds()>=100);
        assertEquals(1,server.paths.size());
    }

    @Test void requestBudgetAndRateLimitStopBeforeMoreRequests() {
        server.add("42"); var client = server.client();
        assertEquals("analysis_window_complete", assertThrows(Failure.class,
                () -> client.prepare(target(null), 40, TOTAL_BYTES, new Budget(1, () -> {}), sha -> {})).code());
        assertEquals(1, server.paths.size());
        server.paths.clear(); server.headers.put("X-RateLimit-Remaining", "0");
        server.headers.put("X-RateLimit-Reset", Long.toString(Instant.now().plusSeconds(120).getEpochSecond()));
        assertEquals("github_rate_limited", assertThrows(Failure.class,
                () -> server.client().prepare(target(null), 40, TOTAL_BYTES, budget(), sha -> {})).code());
        assertEquals(1, server.paths.size());
        server.headers.clear(); server.allStatus = 429; server.headers.put("Retry-After", "150");
        var limited = server.client(); server.paths.clear();
        Failure error = assertThrows(Failure.class, () -> limited.prepare(target(null), 40, TOTAL_BYTES, budget(), sha -> {}));
        assertTrue(error.retrySeconds() >= 150);
        Failure admission = assertThrows(Failure.class, () -> limited.batch(List.of(target(null)), budget()));
        assertTrue(admission.retrySeconds() >= 149 && admission.retrySeconds() <= 151);
        assertThrows(Failure.class, () -> limited.prepare(target(null), 40, TOTAL_BYTES, budget(), sha -> {}));
        assertEquals(1, server.paths.size());
    }

    @Test void statusJsonAndResponseLimitsHaveSafeErrors() {
        server.add("42");
        for (int status : List.of(404, 403, 500, 409)) {
            server.allStatus = status;
            Failure error = assertThrows(Failure.class, () -> server.client().prepare(target(null), 40, TOTAL_BYTES, budget(), sha -> {}));
            assertFalse(error.getMessage().contains("SENSITIVE_RESPONSE"));
            assertEquals(status == 500, error.retrySeconds() >= 0);
        }
        server.allStatus = 0; server.rawResponse = "{broken";
        assertEquals("invalid_github_json", assertThrows(Failure.class, () -> server.client().prepare(target(null), 40, TOTAL_BYTES, budget(), sha -> {})).code());
        server.rawResponse = "x".repeat(65537);
        assertEquals("github_response_too_large", assertThrows(Failure.class, () -> server.client().prepare(target(null), 40, TOTAL_BYTES, budget(), sha -> {})).code());
    }

    @Test void knownRateResetAndConfiguredRequestCapAreRespected() throws Exception {
        server.add("42"); var client = server.client();
        assertEquals("analysis_window_complete", assertThrows(Failure.class,
                () -> client.batch(List.of(target(null)), new Budget(5, () -> {}))).code());
        assertTrue(server.paths.isEmpty());
        Instant reset = Instant.now().plusSeconds(2);
        server.headers.put("X-RateLimit-Remaining", "0");
        server.headers.put("X-RateLimit-Reset", Long.toString(reset.getEpochSecond()));
        assertEquals("github_rate_limited", assertThrows(Failure.class,
                () -> client.prepare(target(null), 1, TOTAL_BYTES, budget(), sha -> {})).code());
        assertThrows(Failure.class, () -> client.batch(List.of(target(null)), budget()));
        assertEquals(1, server.paths.size());
        server.resetWindow();
        client.batch(List.of(target(null)), budget());
        assertFalse(client.prepare(target(null), 1, TOTAL_BYTES, budget(), sha -> {}).files().isEmpty());
    }

    @Test void twentyPublicChecksHaveBoundedConcurrencyAndFreshProof() throws Exception {
        List<PublicAccessVerifier.Source> sources = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String id = Integer.toString(42 + i); server.add(id);
            sources.add(new PublicAccessVerifier.Source(UUID.randomUUID(), id, MockSourceServer.SHA));
        }
        var entered = new java.util.concurrent.CountDownLatch(4);
        var release = new java.util.concurrent.CountDownLatch(1);
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var maximum = new java.util.concurrent.atomic.AtomicInteger();
        server.beforeMetadata = () -> {
            int count = active.incrementAndGet(); maximum.accumulateAndGet(count, Math::max); entered.countDown();
            try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("verification stalled"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { active.decrementAndGet(); }
        };
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var result = pool.submit(() -> server.client().verify(sources));
            try { assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            var proof = result.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(proof.validFor(sources)); assertEquals(4, maximum.get());
            assertEquals(20, server.paths.size()); assertEquals(0, server.authorizationHeaders.get());
        }
    }

    @Test void proofIsBoundToTargetsAndExpires() {
        server.add("42"); var source = new PublicAccessVerifier.Source(UUID.randomUUID(), "42", MockSourceServer.SHA);
        var proof = server.client().verify(List.of(source));
        assertTrue(proof.validFor(List.of(source)));
        assertFalse(proof.validFor(List.of(new PublicAccessVerifier.Source(source.repositoryId(), "42", "c".repeat(40)))));
        assertFalse(new PublicAccessVerifier.Proof(Set.of(source), Instant.now().minusSeconds(31)).validFor(List.of(source)));
    }
}
