package dev.devfield.repoq.project.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.devfield.repoq.project.analysis.AnalysisJobs.Target;
import dev.devfield.repoq.project.analysis.PublicAccessVerifier;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Anonymous API only. Never accepts an authorization header, user token or repository-supplied URL. */
@Component
@ConditionalOnProperty(name = "devfield.project.analysis.source-enabled", havingValue = "true", matchIfMissing = true)
public class GitHubSourceClient implements PublicAccessVerifier {
    public static final int FILE_BYTES = 100 * 1024, TOTAL_BYTES = 2 * 1024 * 1024, MAX_FILES = 40;
    public static class Failure extends RuntimeException {
        private final String code;
        private final int retrySeconds;
        public Failure(String code) { this(code, -1); }
        public Failure(String code, int retrySeconds) { super(code); this.code = code; this.retrySeconds = retrySeconds; }
        public String code() { return code; }
        public int retrySeconds() { return retrySeconds; }
    }
    public static final class Budget {
        private int remaining;
        private int reserved;
        private int treeEntries;
        private final Runnable checkpoint;
        public Budget(int requests, Runnable checkpoint) { this.remaining = requests; this.checkpoint = checkpoint; }
        synchronized void request() {
            checkpoint.run();
            if (remaining <= reserved) throw new Failure("analysis_window_complete", 1);
            remaining--;
        }
        void entries(int count) {
            treeEntries += count;
            if (treeEntries > 50000) throw new Failure("tree_limit_exceeded");
        }
    }
    public record FileEntry(String path, String sha, int size) {}
    public record TextFile(String path, String text) {}
    public record Plan(Target target, String fullName, String commitSha, List<FileEntry> files,
                       Map<String, Integer> exclusions, int byteQuota) {}
    public record Collected(Target target, String commitSha, List<TextFile> files, Map<String, Integer> exclusions, Proof proof) {
        public Collected(Target target, String commitSha, List<TextFile> files, Map<String, Integer> exclusions) {
            this(target, commitSha, files, exclusions, null);
        }
    }

    public synchronized List<Target> batch(List<Target> pending, Budget budget) {
        int capacity = Math.min(budget.remaining, availableRequests());
        List<Target> selected = new ArrayList<>();
        int minimum = 0;
        for (Target target : pending) {
            int cost = target.commitSha() == null ? 7 : 6; // preparation + collection access + checkpoint refresh + one blob
            if (minimum + cost > capacity) break;
            minimum += cost; selected.add(target);
        }
        if (!pending.isEmpty() && selected.isEmpty()) {
            if (budget.remaining < (pending.getFirst().commitSha() == null ? 7 : 6))
                throw new Failure("analysis_window_complete", 1);
            throw new Failure("github_rate_limited", rateWaitSeconds(7));
        }
        return List.copyOf(selected);
    }

    public synchronized void publicationCapacity(int members, Budget budget) {
        if (budget.remaining < members) throw new Failure("analysis_window_complete", 1);
        if (availableRequests() < members) throw new Failure("github_rate_limited", rateWaitSeconds(members));
    }


    public synchronized List<Plan> fitPlans(List<Plan> plans, Budget budget, int publicationRequests) {
        int available = Math.min(budget.remaining, availableRequests()) - plans.size() - publicationRequests;
        long nonempty = plans.stream().filter(p -> !p.files().isEmpty()).count();
        if (budget.remaining < plans.size() + publicationRequests + nonempty) throw new Failure("analysis_window_complete", 1);
        if (available < nonempty) throw new Failure("github_rate_limited", rateWaitSeconds(plans.size() + publicationRequests + (int) nonempty));
        int[] counts = new int[plans.size()];
        // Round-robin allocation keeps an earlier repository from consuming a later member's opportunity.
        for (int round = 0; round < MAX_FILES && available > 0; round++) {
            for (int i = 0; i < plans.size() && available > 0; i++) {
                if (counts[i] < plans.get(i).files().size()) { counts[i]++; available--; }
            }
        }
        List<Plan> result = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) {
            Plan p = plans.get(i);
            Map<String, Integer> exclusions = new TreeMap<>(p.exclusions());
            int skipped = p.files().size() - counts[i];
            if (skipped > 0) exclusions.merge("api_budget", skipped, Integer::sum);
            result.add(new Plan(p.target(), p.fullName(), p.commitSha(),
                    List.copyOf(p.files().subList(0, counts[i])), Map.copyOf(exclusions), p.byteQuota()));
        }
        budget.reserved = publicationRequests + plans.size(); // Publication uses a fresh, mandatory public-access verification.
        return List.copyOf(result);
    }

    private int availableRequests() {
        if (!clock.instant().isBefore(resetAt)) { remaining = 60; resetAt = clock.instant().plusSeconds(3600); }
        return clock.instant().isBefore(blockedUntil) ? 0 : remaining;
    }
    private int rateWaitSeconds(int required) {
        return Math.max(remaining < required ? secondsUntil(resetAt) : 1, secondsUntil(blockedUntil));
    }
    private record Repository(String fullName, String defaultBranch) {}
    private final URI root;
    private final HttpClient http;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private int remaining = 60;
    private Instant resetAt, blockedUntil = Instant.MIN;

    public GitHubSourceClient() { this(URI.create("https://api.github.com")); }
    // Alternate root is for loopback mock servers, never an application property or repository input.
    public GitHubSourceClient(URI root) { this(root, Clock.systemUTC()); }
    public GitHubSourceClient(URI root, Clock clock) {
        if (!root.equals(URI.create("https://api.github.com")) &&
                !("http".equals(root.getScheme()) && "127.0.0.1".equals(root.getHost()) && root.getRawUserInfo() == null))
            throw new IllegalArgumentException("Invalid source API origin");
        this.root = root;
        this.clock = clock;
        this.resetAt = clock.instant().plusSeconds(3600);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Proof verify(List<Source> sources) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Source request inside a transaction");
        Instant started = Instant.now();
        if (sources.isEmpty() || sources.size() > 40 || Set.copyOf(sources).size() != sources.size())
            throw new Failure("invalid_public_access_target");
        Set<String> ids = new LinkedHashSet<>();
        for (Source source : sources) {
            if (!sha(source.commitSha())) throw new Failure("commit_sha_mismatch");
            ids.add(source.githubRepositoryId());
        }
        if (ids.size() > 20) throw new Failure("github_request_budget_exceeded");
        Budget budget = new Budget(20, () -> {});
        // Twenty sequential calls can outlive a 30-second proof. Bound both concurrency and the whole check.
        try (var pool = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(20))) {
            List<Callable<Void>> checks = ids.stream().<Callable<Void>>map(id -> () -> { repository(id, budget); return null; }).toList();
            try {
                for (Future<Void> result : pool.invokeAll(checks, 25, TimeUnit.SECONDS)) {
                    if (result.isCancelled()) throw new Failure("github_timeout", 30);
                    result.get();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new Failure("github_interrupted", 30);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof Failure failure) throw failure;
                throw new Failure("github_unavailable", 30);
            } finally { pool.shutdownNow(); }
        }
        return new Proof(Set.copyOf(sources), started);
    }

    public Plan prepare(Target target, int fileQuota, int byteQuota, Budget budget, Consumer<String> pinned) {
        Repository repo = repository(target.githubRepositoryId(), budget);
        String base = "/repos/" + repo.fullName();
        String commit = target.commitSha();
        if (commit == null) {
            if (repo.defaultBranch() == null || repo.defaultBranch().isBlank()) throw new Failure("empty_repository");
            JsonNode ref = get(base + "/git/ref/heads/" + segment(repo.defaultBranch()), 65536, budget);
            if (!"commit".equals(ref.path("object").path("type").asText())) throw new Failure("invalid_github_response");
            commit = ref.path("object").path("sha").asText();
        }
        if (!sha(commit)) throw new Failure("commit_sha_mismatch");
        pinned.accept(commit);
        JsonNode object = get(base + "/git/commits/" + commit, 256 * 1024, budget);
        if (!commit.equals(object.path("sha").asText()) || !sha(object.path("tree").path("sha").asText()))
            throw new Failure("commit_sha_mismatch");
        String treeSha = object.path("tree").path("sha").asText();
        JsonNode tree = get(base + "/git/trees/" + treeSha + "?recursive=1", 4 * 1024 * 1024, budget);
        if (!tree.path("truncated").isBoolean() || !treeSha.equals(tree.path("sha").asText()) || !tree.path("tree").isArray())
            throw new Failure("invalid_github_response");
        if (tree.path("truncated").booleanValue()) throw new Failure("tree_truncated");
        if (tree.path("tree").size() > 20000) throw new Failure("tree_limit_exceeded");
        budget.entries(tree.path("tree").size());
        List<FileEntry> candidates = new ArrayList<>();
        Map<String, Integer> excluded = new TreeMap<>();
        Set<String> paths = new HashSet<>();
        for (JsonNode entry : tree.path("tree")) {
            String path = entry.path("path").asText();
            if (!safePath(path) || !paths.add(path)) throw new Failure("invalid_github_path");
            if ("tree".equals(entry.path("type").asText())) continue;
            String reason = exclude(path, entry.path("type").asText(), entry.path("mode").asText());
            if (reason == null && (!entry.path("size").canConvertToInt() || entry.path("size").intValue() < 0 || !sha(entry.path("sha").asText())))
                throw new Failure("invalid_github_response");
            if (reason == null && entry.path("size").intValue() > FILE_BYTES) reason = "file_size";
            if (reason != null) { skip(excluded, reason); continue; }
            candidates.add(new FileEntry(path, entry.path("sha").asText(), entry.path("size").intValue()));
        }
        candidates.sort(Comparator.comparingInt((FileEntry e) -> priority(e.path())).thenComparing(FileEntry::path));
        // Reserve a place for code, build declarations and documentation before filling the remaining quota.
        List<FileEntry> ordered = new ArrayList<>();
        for (int category = 0; category < 3; category++) {
            for (FileEntry entry : candidates) if (priority(entry.path()) == category && entry.size() <= byteQuota) {
                ordered.add(entry); break;
            }
        }
        for (FileEntry entry : candidates) if (!ordered.contains(entry)) ordered.add(entry);
        List<FileEntry> selected = new ArrayList<>();
        int bytes = 0;
        for (FileEntry entry : ordered) {
            if (selected.size() >= fileQuota) skip(excluded, "file_budget");
            else if (bytes + entry.size() > byteQuota) skip(excluded, "byte_budget");
            else { selected.add(entry); bytes += entry.size(); }
        }
        return new Plan(target, repo.fullName(), commit, List.copyOf(selected), Map.copyOf(excluded), byteQuota);
    }

    public record Access(String fullName, Proof proof) {}
    public record Download(TextFile file, String exclusion, int bytes) {}

    public Access openCollection(Plan plan, Budget budget) {
        Instant checkedAt = Instant.now();
        Repository current = repository(plan.target().githubRepositoryId(), budget);
        return new Access(current.fullName(), new Proof(Set.of(new Source(plan.target().repositoryId(),
                plan.target().githubRepositoryId(), plan.commitSha())), checkedAt));
    }

    public Access refreshCollection(Plan plan, Access access, Budget budget) {
        var sources=List.of(new Source(plan.target().repositoryId(),plan.target().githubRepositoryId(),plan.commitSha()));
        if (access.proof().validFor(sources)) return access;
        // Preserve checkpoint/publication reservations when a slow collection needs another check.
        if (budget.remaining<=budget.reserved) throw new Failure("analysis_window_complete",1);
        return openCollection(plan,budget);
    }

    public Proof checkpointProof(Plan plan, Budget budget) {
        if (budget.reserved < 1) throw new IllegalStateException("Missing checkpoint verification reservation");
        budget.reserved--;
        return openCollection(plan, budget).proof();
    }

    public Download download(Access access, FileEntry entry, Budget budget, Runnable reserve) {
        JsonNode blob = get("/repos/" + access.fullName() + "/git/blobs/" + entry.sha(), 160 * 1024, budget, reserve);
        if (!entry.sha().equals(blob.path("sha").asText()) || !"base64".equals(blob.path("encoding").asText()) ||
                !blob.path("content").isTextual() || !blob.path("size").canConvertToInt() || blob.path("size").intValue()!=entry.size())
            throw new Failure("invalid_github_response");
        String encoded = blob.path("content").textValue().replace("\n", "").replace("\r", "");
        int padding = encoded.endsWith("==") ? 2 : encoded.endsWith("=") ? 1 : 0;
        // Reject excess decoded body before decoding; a lying Tree cannot bypass a durable byte reservation.
        if (entry.size()<0 || entry.size()>FILE_BYTES || encoded.length()%4!=0 || encoded.length()/4*3-padding!=entry.size())
            throw new Failure("blob_size_mismatch");
        byte[] data;
        try { data = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException e) { throw new Failure("invalid_github_response"); }
        if (data.length!=entry.size() || !entry.sha().equals(blobSha(data))) throw new Failure("blob_sha_mismatch");
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString(); }
        catch (CharacterCodingException e) { return new Download(null,"binary",data.length); }
        if (text.indexOf('\0')>=0 || text.chars().anyMatch(c->c<32 && c!='\n' && c!='\r' && c!='\t')) return new Download(null,"binary",data.length);
        if (text.startsWith("version https://git-lfs.github.com/spec/v1")) return new Download(null,"git_lfs",data.length);
        return new Download(new TextFile(entry.path(),text),null,data.length);
    }

    public Collected collect(Plan plan, Budget budget) {
        Access access = openCollection(plan,budget);
        List<TextFile> files = new ArrayList<>(); Map<String,Integer> excluded = new TreeMap<>(plan.exclusions()); int bytes=0;
        for (FileEntry entry:plan.files()) {
            Download result = download(access,entry,budget,()->{});
            if ((bytes+=result.bytes())>plan.byteQuota()) throw new Failure("collection_byte_budget_exceeded");
            if (result.file()!=null) files.add(result.file()); else skip(excluded,result.exclusion());
        }
        return new Collected(plan.target(),plan.commitSha(),List.copyOf(files),Map.copyOf(excluded),access.proof());
    }

    private Repository repository(String id, Budget budget) {
        if (id == null || !id.matches("[1-9][0-9]{0,18}")) throw new Failure("repository_id_mismatch");
        JsonNode node = get("/repositories/" + id, 65536, budget);
        if (!node.path("id").isIntegralNumber() || !id.equals(node.path("id").asText())) throw new Failure("repository_id_mismatch");
        if (!node.path("private").isBoolean() || node.path("private").booleanValue() ||
                !"public".equals(node.path("visibility").asText())) throw new Failure("repository_not_public");
        String name = node.path("full_name").asText();
        if (!name.matches("[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}")) throw new Failure("invalid_github_response");
        return new Repository(name, node.path("default_branch").asText(null));
    }

    private JsonNode get(String path, int maxBytes, Budget budget) { return get(path,maxBytes,budget,()->{}); }

    private JsonNode get(String path, int maxBytes, Budget budget, Runnable reserve) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Source request inside a transaction");
        synchronized (this) {
            // A known remote shortage takes precedence over a local window rollover.
            if (budget.remaining <= budget.reserved && availableRequests() <= budget.reserved)
                throw new Failure("github_rate_limited", rateWaitSeconds(budget.reserved + 1));
        }
        budget.request();
        synchronized (this) {
            Instant now = clock.instant();
            if (!now.isBefore(resetAt)) { remaining = 60; resetAt = now.plusSeconds(3600); }
            if (now.isBefore(blockedUntil)) throw new Failure("github_rate_limited", secondsUntil(blockedUntil));
            if (remaining <= 0) throw new Failure("github_rate_limited", secondsUntil(resetAt));
            if (remaining <= budget.reserved) throw new Failure("github_rate_limited", secondsUntil(resetAt));
            remaining--;
        }
        reserve.run();
        var request = HttpRequest.newBuilder(root.resolve(path)).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "DevField-RepoQ-PublicAnalysis").GET().build();
        var future = http.sendAsync(request, info -> new LimitedBody(maxBytes));
        try {
            var response = future.get(10, TimeUnit.SECONDS);
            synchronized (this) {
                response.headers().firstValue("X-RateLimit-Remaining").ifPresent(v -> {
                    try { remaining = Math.min(remaining, Math.max(0, Integer.parseInt(v))); } catch (NumberFormatException ignored) { }
                });
                response.headers().firstValue("X-RateLimit-Reset").ifPresent(v -> {
                    try { Instant reset = Instant.ofEpochSecond(Long.parseLong(v)); if (reset.isAfter(clock.instant())) resetAt = reset; }
                    catch (RuntimeException ignored) { }
                });
                int status = response.statusCode();
                if (status == 403 || status == 429) {
                    String message = new String(response.body(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                    if (status == 429 || remaining == 0 || response.headers().firstValue("Retry-After").isPresent() || message.contains("rate limit")) {
                        Instant retry = retryAt(response.headers());
                        if (retry.isAfter(blockedUntil)) blockedUntil = retry;
                        throw new Failure("github_rate_limited", secondsUntil(blockedUntil));
                    }
                    throw new Failure("github_access_denied");
                }
                if (status == 404) throw new Failure("github_not_found");
                if (status == 409) throw new Failure("empty_repository");
                if (status >= 500) throw new Failure("github_unavailable", 30);
                if (status != 200) throw new Failure("github_response_rejected");
            }
            JsonNode value;
            try { value = json.readTree(response.body()); }
            catch (Exception e) { throw new Failure("invalid_github_json"); }
            if (value == null || !value.isObject()) throw new Failure("invalid_github_json");
            return value;
        } catch (TimeoutException e) { throw new Failure("github_timeout", 30); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Failure("github_interrupted", 30); }
        catch (ExecutionException e) {
            Throwable cause = e.getCause();
            while (cause.getCause() != null && !(cause instanceof Failure)) cause = cause.getCause();
            if (cause instanceof Failure failure) throw failure;
            throw new Failure(cause instanceof HttpTimeoutException ? "github_timeout" : "github_unavailable", 30);
        } finally { future.cancel(true); }
    }

    private Instant retryAt(HttpHeaders headers) {
        Instant retry = clock.instant().plusSeconds(60);
        Optional<String> after = headers.firstValue("Retry-After");
        if (after.isPresent()) {
            try { retry = clock.instant().plusSeconds(Math.max(1, Long.parseLong(after.get()))); }
            catch (RuntimeException e) {
                try { retry = ZonedDateTime.parse(after.get(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
                catch (RuntimeException ignored) { }
            }
        }
        if (remaining == 0 && resetAt.isAfter(retry)) retry = resetAt;
        return retry;
    }
    private int secondsUntil(Instant time) {
        return (int) Math.min(86400, Math.max(1, Duration.between(clock.instant(), time).getSeconds() + 1));
    }
    public static boolean safePath(String path) {
        return path != null && !path.isBlank() && path.length() <= 1024 && !path.contains("\\") && !path.contains(":") &&
                path.chars().noneMatch(Character::isISOControl) && Arrays.stream(path.split("/", -1))
                .noneMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."));
    }
    private static String exclude(String path, String type, String mode) {
        String p = path.toLowerCase(Locale.ROOT);
        if (!"blob".equals(type) || !Set.of("100644", "100755").contains(mode)) return "non_regular";
        if (p.matches(".*(^|/)(node_modules|vendor|build|dist|target|[.]git|generated|generated-sources)(/.*)?")) return "generated_or_vendor";
        if (p.matches(".*(^|/)([.]env[^/]*|credentials[^/]*|secrets?[^/]*|id_rsa[^/]*|[^/]*[.](pem|key|p12|pfx|jks|keystore))$") ||
                p.contains("github_pat_") || p.contains("ghp_")) return "credential_path";
        if (p.endsWith(".lock") || p.endsWith("-lock.json") || p.endsWith(".min.js") || p.contains("/test/")) return "generated_or_lockfile";
        return p.endsWith(".java") || p.endsWith("/pom.xml") || p.equals("pom.xml") || p.endsWith("build.gradle") ||
                p.endsWith("build.gradle.kts") || p.matches("(^|.*/)(readme[^/]*[.]md|docs/.*[.]md)") ? null : "unsupported_file";
    }
    private static int priority(String path) { return path.endsWith(".java") ? 0 : path.toLowerCase(Locale.ROOT).endsWith(".md") ? 2 : 1; }
    private static String segment(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static boolean sha(String value) { return value != null && value.matches("[0-9a-f]{40}"); }
    private static void skip(Map<String, Integer> excluded, String reason) { excluded.merge(reason, 1, Integer::sum); }
    public static String blobSha(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + data.length + "\0").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception e) { throw new IllegalStateException("Git object hashing unavailable"); }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > limit) {
                    subscription.cancel(); result.completeExceptionally(new Failure("github_response_too_large")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
