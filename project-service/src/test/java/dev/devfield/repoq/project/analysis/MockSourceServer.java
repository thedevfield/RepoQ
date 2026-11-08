package dev.devfield.repoq.project.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.devfield.repoq.project.github.GitHubSourceClient;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

final class MockSourceServer implements AutoCloseable {
    static final String SHA = "a".repeat(40), TREE = "b".repeat(40);
    static final String JAVA = """
            import org.springframework.web.bind.annotation.RestController;
            import org.springframework.transaction.annotation.Transactional;
            @RestController
            public class App {
                @Transactional public String get() { return "safe"; }
            }
            """;
    static final class Repo {
        final String id;
        String name, returnedId, head = SHA;
        boolean privateRepo, truncated, badBlobSha, badCommitSha;
        String authenticatedOnlyCommit;
        int status;
        String mode = "100644";
        final Map<String, String> files = new LinkedHashMap<>();
        Repo(String id) { this.id = id; this.returnedId = id; this.name = "owner/repo" + id; files.put("src/App.java", JAVA); }
    }
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
    final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };
    void resetWindow() { now.updateAndGet(t -> t.plusSeconds(3601)); headers.clear(); }
    final Map<String, Repo> repos = new ConcurrentHashMap<>();
    final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    final List<String> authenticatedPaths = Collections.synchronizedList(new ArrayList<>());
    final Map<String, String> authenticatedHeaders = new ConcurrentHashMap<>();
    volatile int authenticatedStatus;
    final AtomicInteger contentRequests = new AtomicInteger(), authorizationHeaders = new AtomicInteger();
    final Map<String, String> headers = new ConcurrentHashMap<>();
    volatile Runnable beforeMetadata = () -> {};
    final ExecutorService httpExecutor = Executors.newFixedThreadPool(8);
    volatile Runnable beforeBlob = () -> {};
    volatile int allStatus;
    volatile String rawResponse;
    final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    MockSourceServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(httpExecutor);
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath(); paths.add(path);
                if (path.startsWith("/repositories/")) beforeMetadata.run();
                boolean authenticated = exchange.getRequestHeaders().containsKey("Authorization");
                if (authenticated) {
                    authorizationHeaders.incrementAndGet();
                    authenticatedPaths.add(path);
                }
                Object body = Map.of("message", "SENSITIVE_RESPONSE");
                int status = authenticated && authenticatedStatus != 0 ? authenticatedStatus : allStatus;
                Repo repo = null;
                if (path.startsWith("/repositories/")) repo = repos.get(path.substring("/repositories/".length()));
                else for (Repo value : repos.values()) if (path.startsWith("/repos/" + value.name + "/")) { repo = value; break; }
                if (status == 0) {
                    if (repo == null) status = 404;
                    else if (repo.status != 0) status = repo.status;
                    else if (path.startsWith("/repositories/")) {
                        status = 200; body = Map.of("id", Long.parseLong(repo.returnedId), "private", repo.privateRepo,
                                "visibility", repo.privateRepo ? "private" : "public", "full_name", repo.name, "default_branch", "main");
                    } else if (path.contains("/git/ref/heads/")) {
                        status = 200; body = Map.of("object", Map.of("type", "commit", "sha", repo.head));
                    } else if (path.contains("/git/commits/")) {
                        String commit = path.substring(path.lastIndexOf('/') + 1);
                        if (!authenticated && commit.equals(repo.authenticatedOnlyCommit)) status = 404;
                        else {
                            status = 200; body = Map.of("sha", repo.badCommitSha ? "c".repeat(40) : commit, "tree", Map.of("sha", TREE));
                        }
                    } else if (path.contains("/git/trees/")) {
                        List<Map<String, Object>> entries = new ArrayList<>();
                        for (var file : repo.files.entrySet()) {
                            byte[] data = file.getValue().getBytes(StandardCharsets.UTF_8);
                            entries.add(Map.of("path", file.getKey(), "type", "blob", "mode", repo.mode, "size", data.length, "sha", GitHubSourceClient.blobSha(data)));
                        }
                        status = 200; body = Map.of("sha", TREE, "truncated", repo.truncated, "tree", entries);
                    } else if (path.contains("/git/blobs/")) {
                        contentRequests.incrementAndGet(); beforeBlob.run();
                        if (repo.privateRepo) status = 404;
                        else {
                            String sha = path.substring(path.lastIndexOf('/') + 1);
                            for (String text : repo.files.values()) {
                                byte[] data = text.getBytes(StandardCharsets.UTF_8);
                                if (GitHubSourceClient.blobSha(data).equals(sha)) {
                                    status = 200; body = Map.of("sha", repo.badBlobSha ? "d".repeat(40) : sha, "size", data.length,
                                            "encoding", "base64", "content", Base64.getEncoder().encodeToString(data)); break;
                                }
                            }
                        }
                    }
                }
                if (status == 0) status = 404;
                byte[] bytes = rawResponse == null ? json.writeValueAsBytes(body) : rawResponse.getBytes(StandardCharsets.UTF_8);
                headers.forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
                if (authenticated) authenticatedHeaders.forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
                exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
    }
    Repo add(String id) { Repo repo = new Repo(id); repos.put(id, repo); return repo; }
    GitHubSourceClient client() { return client(""); }
    GitHubSourceClient client(String token) {
        return new GitHubSourceClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), clock, token);
    }
    @Override public void close() { server.stop(0); httpExecutor.shutdownNow(); }
}
