package dev.devfield.repoq.project.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.devfield.repoq.project.api.ApiProblem;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class GitHubAppClient {
    public record Tokens(String access, String refresh, Instant accessExpiry, Instant refreshExpiry) {}
    public record Repository(long id, String fullName, String name, boolean isPrivate) {}
    public record RepositoryPage(List<Repository> repositories, boolean hasNext) {}
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String clientId;
    private final String clientSecret;
    private final Duration timeout;

    public GitHubAppClient(@Value("${devfield.project.github.client-id:}") String clientId,
                           @Value("${devfield.project.github.client-secret:}") String clientSecret,
                           @Value("${devfield.project.github.api-timeout-seconds:8}") long timeoutSeconds) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        if (timeoutSeconds < 1 || timeoutSeconds > 60) throw new IllegalArgumentException("Invalid GitHub timeout");
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public Tokens exchange(String code, String verifier, String redirectUri) {
        return tokens("code=" + encode(code) + "&redirect_uri=" + encode(redirectUri) + "&code_verifier=" + encode(verifier));
    }

    public Tokens refresh(String refreshToken) {
        return tokens("grant_type=refresh_token&refresh_token=" + encode(refreshToken));
    }

    private Tokens tokens(String payload) {
        if (clientId.isBlank() || clientSecret.isBlank())
            throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_configuration_unavailable");
        JsonNode n = request("POST", URI.create("https://github.com/login/oauth/access_token"), null,
                "client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret) + "&" + payload);
        String access = n.path("access_token").asText("");
        String refresh = n.path("refresh_token").asText("");
        long expires = n.path("expires_in").asLong(0);
        long refreshExpires = n.path("refresh_token_expires_in").asLong(0);
        if (access.isBlank() || refresh.isBlank() || expires < 1 || refreshExpires < 1)
            throw new ApiProblem(HttpStatus.UNAUTHORIZED, "github_reauthorization_required");
        Instant now = Instant.now();
        return new Tokens(access, refresh, now.plusSeconds(expires), now.plusSeconds(refreshExpires));
    }

    public long userId(String token) {
        long id = request("GET", URI.create("https://api.github.com/user"), token, null).path("id").asLong(0);
        if (id < 1) throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_unavailable");
        return id;
    }

    public boolean installationAccessible(String token, long installationId, long appId) {
        int page = 1;
        while (page <= 100) {
            JsonNode result = request("GET", URI.create("https://api.github.com/user/installations?per_page=100&page=" + page), token, null);
            JsonNode items = result.path("installations");
            if (!items.isArray()) throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_unavailable");
            for (JsonNode item : items) {
                if (item.path("id").asLong() == installationId && item.path("app_id").asLong() == appId &&
                        item.path("suspended_at").isNull() && item.path("permissions").path("contents").asText().matches("read|write"))
                    return true;
            }
            if (items.size() < 100) return false;
            page++;
        }
        throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_pagination_limit");
    }

    public RepositoryPage repositories(String token, long installationId, int page, int size) {
        JsonNode n = request("GET", URI.create("https://api.github.com/user/installations/" + installationId +
                "/repositories?per_page=" + size + "&page=" + page), token, null);
        JsonNode items = n.path("repositories");
        if (!items.isArray()) throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_unavailable");
        List<Repository> repos = new ArrayList<>();
        for (JsonNode item : items) {
            if (item.path("id").asLong() < 1 || !item.path("private").isBoolean() ||
                    !item.path("full_name").isTextual() || !item.path("name").isTextual())
                throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_unavailable");
            repos.add(new Repository(item.path("id").asLong(), item.path("full_name").asText(),
                    item.path("name").asText(), item.path("private").asBoolean()));
        }
        return new RepositoryPage(repos, repos.size() == size && n.path("total_count").asLong(0) > (long)page * size);
    }

    private JsonNode request(String method, URI uri, String token, String body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "DevField-RepoQ");
            if (token != null) b.header("Authorization", "Bearer " + token);
            if (body == null) b.GET();
            else b.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<byte[]> response = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status == 429 || (status == 403 && response.headers().firstValue("X-RateLimit-Remaining").orElse("").equals("0")))
                throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_rate_limited");
            if (status == 401) throw new ApiProblem(HttpStatus.UNAUTHORIZED, "github_reauthorization_required");
            if (status == 403) throw new ApiProblem(HttpStatus.FORBIDDEN, "github_access_denied");
            if (status == 404) throw new ApiProblem(HttpStatus.FORBIDDEN, "github_installation_unavailable");
            if (status != 200 || response.body().length > 1024 * 1024)
                throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_unavailable");
            return mapper.readTree(response.body());
        } catch (ApiProblem e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_unavailable");
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
