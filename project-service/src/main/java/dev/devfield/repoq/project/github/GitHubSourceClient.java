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
        private int treeEntries;
        private final Runnable checkpoint;
        public Budget(int requests, Runnable checkpoint) { this.remaining = requests; this.checkpoint = checkpoint; }
        void request() {
            checkpoint.run();
            if (remaining-- <= 0) throw new Failure("github_request_budget_exceeded");
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
    public record Collected(Target target, String commitSha, List<TextFile> files, Map<String, Integer> exclusions) {}
    private record Repository(String fullName, String defaultBranch) {}
    private final URI root;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private int remaining = 60;
    private Instant resetAt = Instant.now().plusSeconds(3600), blockedUntil = Instant.MIN;

    public GitHubSourceClient() { this(URI.create("https://api.github.com")); }
    // Alternate root is for loopback mock servers, never an application property or repository input.
    public GitHubSourceClient(URI root) {
        if (!root.equals(URI.create("https://api.github.com")) &&
                !("http".equals(root.getScheme()) && "127.0.0.1".equals(root.getHost()) && root.getRawUserInfo() == null))
            throw new IllegalArgumentException("Invalid source API origin");
        this.root = root;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Proof verify(List<Source> sources) {
        Instant started = Instant.now();
        if (sources.isEmpty() || sources.size() > 40 || Set.copyOf(sources).size() != sources.size())
            throw new Failure("invalid_public_access_target");
        Budget budget = new Budget(20, () -> {});
        Set<String> checked = new HashSet<>();
        for (Source source : sources) {
            if (!sha(source.commitSha())) throw new Failure("commit_sha_mismatch");
            if (checked.add(source.githubRepositoryId())) repository(source.githubRepositoryId(), budget);
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

    public Collected collect(Plan plan, Budget budget) {
        Repository current = repository(plan.target().githubRepositoryId(), budget);
        List<TextFile> files = new ArrayList<>();
        Map<String, Integer> excluded = new TreeMap<>(plan.exclusions());
        int bytes = 0;
        for (FileEntry entry : plan.files()) {
            JsonNode blob = get("/repos/" + current.fullName() + "/git/blobs/" + entry.sha(), 160 * 1024, budget);
            if (!entry.sha().equals(blob.path("sha").asText()) || !"base64".equals(blob.path("encoding").asText()) ||
                    !blob.path("content").isTextual() || !blob.path("size").canConvertToInt()) throw new Failure("invalid_github_response");
            byte[] data;
            try { data = Base64.getDecoder().decode(blob.path("content").textValue().replace("\n", "").replace("\r", "")); }
            catch (IllegalArgumentException e) { throw new Failure("invalid_github_response"); }
            if (data.length != entry.size() || data.length != blob.path("size").intValue() || !entry.sha().equals(blobSha(data)))
                throw new Failure("blob_sha_mismatch");
            if (data.length > FILE_BYTES || (bytes += data.length) > plan.byteQuota()) throw new Failure("collection_byte_budget_exceeded");
            String text;
            try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString(); }
            catch (CharacterCodingException e) { skip(excluded, "binary"); continue; }
            if (text.indexOf('\0') >= 0 || text.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\r' && c != '\t')) { skip(excluded, "binary"); continue; }
            if (text.startsWith("version https://git-lfs.github.com/spec/v1")) { skip(excluded, "git_lfs"); continue; }
            files.add(new TextFile(entry.path(), text));
        }
        return new Collected(plan.target(), plan.commitSha(), List.copyOf(files), Map.copyOf(excluded));
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

    private synchronized JsonNode get(String path, int maxBytes, Budget budget) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Source request inside a transaction");
        budget.request();
        Instant now = Instant.now();
        if (!now.isBefore(resetAt)) { remaining = 60; resetAt = now.plusSeconds(3600); }
        if (now.isBefore(blockedUntil)) throw new Failure("github_rate_limited", secondsUntil(blockedUntil));
        if (remaining <= 0) throw new Failure("github_rate_limited", secondsUntil(resetAt));
        remaining--;
        var request = HttpRequest.newBuilder(root.resolve(path)).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "DevField-RepoQ-PublicAnalysis").GET().build();
        var future = http.sendAsync(request, info -> new LimitedBody(maxBytes));
        try {
            var response = future.get(10, TimeUnit.SECONDS);
            response.headers().firstValue("X-RateLimit-Remaining").ifPresent(v -> {
                try { remaining = Math.min(remaining, Math.max(0, Integer.parseInt(v))); } catch (NumberFormatException ignored) { }
            });
            response.headers().firstValue("X-RateLimit-Reset").ifPresent(v -> {
                try { Instant reset = Instant.ofEpochSecond(Long.parseLong(v)); if (reset.isAfter(Instant.now())) resetAt = reset; }
                catch (RuntimeException ignored) { }
            });
            int status = response.statusCode();
            if (status == 403 || status == 429) {
                String message = new String(response.body(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if (status == 429 || remaining == 0 || response.headers().firstValue("Retry-After").isPresent() || message.contains("rate limit")) {
                    blockedUntil = retryAt(response.headers());
                    throw new Failure("github_rate_limited", secondsUntil(blockedUntil));
                }
                throw new Failure("github_access_denied");
            }
            if (status == 404) throw new Failure("github_not_found");
            if (status == 409) throw new Failure("empty_repository");
            if (status >= 500) throw new Failure("github_unavailable", 30);
            if (status != 200) throw new Failure("github_response_rejected");
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
        Instant retry = Instant.now().plusSeconds(60);
        Optional<String> after = headers.firstValue("Retry-After");
        if (after.isPresent()) {
            try { retry = Instant.now().plusSeconds(Math.max(1, Long.parseLong(after.get()))); }
            catch (RuntimeException e) {
                try { retry = ZonedDateTime.parse(after.get(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
                catch (RuntimeException ignored) { }
            }
        }
        if (remaining == 0 && resetAt.isAfter(retry)) retry = resetAt;
        return retry;
    }
    private static int secondsUntil(Instant time) {
        return (int) Math.min(86400, Math.max(1, Duration.between(Instant.now(), time).getSeconds() + 1));
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
