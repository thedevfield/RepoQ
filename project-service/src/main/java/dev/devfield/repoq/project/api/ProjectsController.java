package dev.devfield.repoq.project.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.devfield.repoq.project.data.Projects;
import dev.devfield.repoq.project.github.GitHubRepositoryClient;
import dev.devfield.repoq.project.github.GitHubUrl;
import dev.devfield.repoq.project.security.AuthSessionVerifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectsController {
    public record RepositoryInput(String repositoryUrl, String githubRepositoryId, String visibility,
                                  JsonNode consents) {}
    public record RegisterRequest(String repositoryUrl, String name, List<RepositoryInput> repositories,
                                  String githubRepositoryId, JsonNode consents) {}
    public record AddRequest(List<RepositoryInput> repositories) {}

    private final AuthSessionVerifier sessions;
    private final GitHubRepositoryClient github;
    private final Projects projects;

    public ProjectsController(AuthSessionVerifier sessions, GitHubRepositoryClient github, Projects projects) {
        this.sessions = sessions;
        this.github = github;
        this.projects = projects;
    }

    @PostMapping
    public ResponseEntity<Projects.Registration> register(@RequestHeader("Authorization") String bearer,
                                                            @AuthenticationPrincipal Jwt jwt,
                                                            @RequestHeader(value = "Idempotency-Key", required = false)
                                                            String key,
                                                            @RequestBody(required = false) RegisterRequest request) {
        sessions.verify(bearer);
        if (request == null || request.githubRepositoryId() != null || request.consents() != null)
            throw invalid();
        List<RepositoryInput> inputs;
        if (request.repositories() == null) {
            inputs = List.of(new RepositoryInput(request.repositoryUrl(), null, null, null));
        } else {
            if (request.repositoryUrl() != null) throw invalid();
            inputs = request.repositories();
        }
        String requestedName = request.name() == null ? null : request.name().trim();
        if (requestedName != null && (requestedName.isBlank() || requestedName.length() > 100))
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_name");
        String safeKey = checkKey(key);
        var urls = parsePublic(inputs);
        UUID owner = UUID.fromString(jwt.getSubject());
        String hash = fingerprint(requestedName == null ? "<default>" : requestedName, urls);
        if (safeKey != null) {
            var replay = projects.replayRegistration(owner, safeKey, hash);
            if (replay != null)
                return ResponseEntity.created(URI.create("/api/projects/" + replay.id())).body(replay);
        }
        var repositories = fetchPublic(urls);
        String name = requestedName == null ? repositories.getFirst().name() : requestedName;
        var result = projects.register(owner, repositories, name, safeKey, hash);
        return ResponseEntity.created(URI.create("/api/projects/" + result.id())).body(result);
    }

    @GetMapping
    public List<Projects.Item> list(@RequestHeader("Authorization") String bearer, @AuthenticationPrincipal Jwt jwt) {
        sessions.verify(bearer);
        return projects.list(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/{id}")
    public Projects.Detail detail(@RequestHeader("Authorization") String bearer, @AuthenticationPrincipal Jwt jwt,
                                  @PathVariable UUID id) {
        sessions.verify(bearer);
        return projects.detail(UUID.fromString(jwt.getSubject()), id);
    }

    @PostMapping("/{id}/repositories")
    public ResponseEntity<Projects.Addition> add(@RequestHeader("Authorization") String bearer,
                                                  @AuthenticationPrincipal Jwt jwt,
                                                  @PathVariable UUID id,
                                                  @RequestHeader(value = "Idempotency-Key", required = false)
                                                  String key,
                                                  @RequestBody(required = false) AddRequest request) {
        sessions.verify(bearer);
        if (request == null) throw invalid();
        UUID owner = UUID.fromString(jwt.getSubject());
        projects.requireOwned(owner, id);
        String safeKey = checkKey(key);
        var urls = parsePublic(request.repositories());
        String hash = fingerprint("ADD", urls);
        if (safeKey != null) {
            var replay = projects.replayAddition(owner, id, safeKey, hash);
            if (replay != null) return ResponseEntity.status(HttpStatus.CREATED).body(replay);
        }
        var repositories = fetchPublic(urls);
        var result = projects.add(owner, id, repositories, safeKey, hash);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @DeleteMapping("/{id}/repositories/{memberId}")
    public ResponseEntity<Void> remove(@RequestHeader("Authorization") String bearer,
                                       @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                       @PathVariable UUID memberId) {
        sessions.verify(bearer);
        projects.remove(UUID.fromString(jwt.getSubject()), id, memberId);
        return ResponseEntity.noContent().build();
    }

    private List<GitHubUrl> parsePublic(List<RepositoryInput> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > 20) throw invalid();
        var result = new ArrayList<GitHubUrl>(inputs.size());
        for (RepositoryInput input : inputs) {
            if (input == null || input.githubRepositoryId() != null || input.consents() != null ||
                    (input.visibility() != null && !"PUBLIC".equals(input.visibility())))
                throw new ApiProblem(HttpStatus.BAD_REQUEST, "private_repository_not_supported");
            result.add(GitHubUrl.parse(input.repositoryUrl()));
        }
        return result;
    }

    private List<GitHubRepositoryClient.Repository> fetchPublic(List<GitHubUrl> urls) {
        var result = new ArrayList<GitHubRepositoryClient.Repository>(urls.size());
        for (GitHubUrl url : urls) result.add(github.fetch(url.owner(), url.repo()));
        return result;
    }

    private static String checkKey(String key) {
        if (key == null) return null;
        if (key.isBlank() || key.length() > 128 || !key.matches("[A-Za-z0-9._:-]+"))
            throw invalid();
        return key;
    }

    private static String fingerprint(String label, List<GitHubUrl> urls) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(label.getBytes(StandardCharsets.UTF_8));
            for (var url : urls) {
                digest.update((byte) 0);
                digest.update(url.owner().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '/');
                digest.update(url.repo().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiProblem invalid() { return new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_request"); }
}
