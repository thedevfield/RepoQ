package dev.devfield.repoq.project.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.devfield.repoq.project.api.ApiProblem;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class GitHubApiClient implements GitHubRepositoryClient {
    private final HttpClient client;
    private final URI apiRoot;
    private final ObjectMapper json = new ObjectMapper();
    private final String token;

    @Autowired
    public GitHubApiClient(@Value("${devfield.project.github.token:}") String token) {
        this(token, URI.create("https://api.github.com"), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    GitHubApiClient(String token, URI apiRoot, HttpClient client) {
        this.token = token;
        this.apiRoot = apiRoot;
        this.client = client;
    }

    @Override
    public Repository fetch(String owner, String repo) {
        try {
            URI uri = apiRoot.resolve("/repos/" + owner + "/" + repo);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(7))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "DevField-RepoQ")
                    .GET();
            if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
            HttpResponse<java.io.InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                int status = response.statusCode();
                if (status == 404) throw new ApiProblem(HttpStatus.BAD_REQUEST, "repository_unavailable");
                if (status == 429 || (status == 403 && response.headers().firstValue("X-RateLimit-Remaining").orElse("").equals("0")))
                    throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_rate_limited");
                if (status != 200) throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_unavailable");
                byte[] data = stream.readNBytes(65537);
                if (data.length > 65536) throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_unavailable");
                JsonNode node = json.readTree(data);
                if (node == null || !node.path("id").canConvertToLong() || node.path("id").longValue() <= 0 ||
                        !node.path("private").isBoolean() || node.path("private").booleanValue() ||
                        !node.path("full_name").isTextual() || !node.path("name").isTextual() ||
                        !node.path("full_name").textValue().matches("[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}") ||
                        !node.path("name").textValue().matches("[A-Za-z0-9_.-]{1,100}"))
                    throw new ApiProblem(HttpStatus.BAD_REQUEST, "repository_unavailable");
                return new Repository(Long.toString(node.path("id").longValue()),
                        node.path("full_name").textValue(), node.path("name").textValue());
            }
        } catch (ApiProblem e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_unavailable");
        }
    }
}
