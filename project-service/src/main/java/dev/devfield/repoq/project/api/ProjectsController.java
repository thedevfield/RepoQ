package dev.devfield.repoq.project.api;

import tools.jackson.databind.JsonNode;
import dev.devfield.repoq.project.data.Projects;
import dev.devfield.repoq.project.deletion.ProjectDeletions;
import dev.devfield.repoq.project.analysis.ProjectAnalysisReads;
import dev.devfield.repoq.project.github.GitHubRepositoryClient;
import dev.devfield.repoq.project.github.GitHubConnections;
import dev.devfield.repoq.project.github.GitHubUrl;
import dev.devfield.repoq.project.security.AuthSessionVerifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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
    private record Input(GitHubUrl url, String id, List<Projects.Consent> consents) {}

    private final AuthSessionVerifier sessions;
    private final GitHubRepositoryClient github;
    private final Projects projects;
    private final ProjectAnalysisReads reads;
    private final ProjectDeletions deletions;
    private final GitHubConnections connections;
    private final boolean privateEnabled;
    private final String policyVersion;

    public ProjectsController(AuthSessionVerifier sessions, GitHubRepositoryClient github, Projects projects,
                              GitHubConnections connections, ProjectAnalysisReads reads, ProjectDeletions deletions,
                              @Value("${devfield.project.github.private-registration-enabled:false}") String enabled,
                              @Value("${devfield.project.github.private-consent-policy-version:}") String policyVersion) {
        this.sessions = sessions;
        this.github = github;
        this.projects = projects;
        this.reads = reads;
        this.deletions = deletions;
        this.connections = connections;
        this.privateEnabled = "true".equals(enabled);
        this.policyVersion = policyVersion;
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
        var normalized = parse(inputs);
        UUID owner = UUID.fromString(jwt.getSubject());
        String hash = fingerprint(requestedName == null ? "<default>" : requestedName, normalized);
        if (safeKey != null) {
            var replay = projects.replayRegistration(owner, safeKey, hash);
            if (replay != null)
                return ResponseEntity.created(URI.create("/api/projects/" + replay.id())).body(replay);
        }
        var repositories = fetch(owner, normalized);
        String name = requestedName == null ? repositories.getFirst().identity().name() : requestedName;
        var result = projects.registerMixed(owner, repositories, name, safeKey, hash);
        return ResponseEntity.created(URI.create("/api/projects/" + result.id())).body(result);
    }

    @GetMapping
    public List<Projects.Item> list(@RequestHeader("Authorization") String bearer, @AuthenticationPrincipal Jwt jwt) {
        sessions.verify(bearer);
        return reads.list(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/{id}")
    public Projects.Detail detail(@RequestHeader("Authorization") String bearer, @AuthenticationPrincipal Jwt jwt,
                                  @PathVariable UUID id) {
        sessions.verify(bearer);
        return reads.detail(UUID.fromString(jwt.getSubject()), id);
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
        var normalized = parse(request.repositories());
        String hash = fingerprint("ADD", normalized);
        if (safeKey != null) {
            var replay = projects.replayAddition(owner, id, safeKey, hash);
            if (replay != null) return ResponseEntity.status(HttpStatus.CREATED).body(replay);
        }
        var repositories = fetch(owner, normalized);
        var result = projects.addMixed(owner, id, repositories, safeKey, hash);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ProjectDeletions.Pending> delete(@RequestHeader("Authorization") String bearer,
                                                           @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        sessions.verify(bearer);
        var pending = deletions.request(UUID.fromString(jwt.getSubject()), id);
        return pending == null ? ResponseEntity.noContent().build() : ResponseEntity.accepted().body(pending);
    }

    @DeleteMapping("/{id}/repositories/{memberId}")
    public ResponseEntity<Void> remove(@RequestHeader("Authorization") String bearer,
                                       @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                       @PathVariable UUID memberId) {
        sessions.verify(bearer);
        projects.remove(UUID.fromString(jwt.getSubject()), id, memberId);
        return ResponseEntity.noContent().build();
    }

    private List<Input> parse(List<RepositoryInput> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > 20) throw invalid();
        var result = new ArrayList<Input>(inputs.size());
        for (RepositoryInput input : inputs) {
            if (input == null) throw invalid();
            if (input.githubRepositoryId() != null) {
                if (!privateEnabled) throw new ApiProblem(HttpStatus.BAD_REQUEST, "private_repository_not_supported");
                if (input.repositoryUrl() != null ||
                        (input.visibility() != null && !"PRIVATE".equals(input.visibility())) ||
                        !input.githubRepositoryId().matches("[1-9][0-9]{0,18}")) throw invalid();
                result.add(new Input(null, input.githubRepositoryId(), consents(input.consents())));
                continue;
            }
            if (input.consents() != null || (input.visibility() != null && !"PUBLIC".equals(input.visibility())))
                throw new ApiProblem(HttpStatus.BAD_REQUEST, "private_repository_not_supported");
            result.add(new Input(GitHubUrl.parse(input.repositoryUrl()), null, List.of()));
        }
        return result;
    }

    private List<Projects.Consent> consents(JsonNode node) {
        if (policyVersion.isBlank() || node == null || !node.isArray() || node.size() != 2) throw invalid();
        var result = new ArrayList<Projects.Consent>(2);
        for (JsonNode item : node) {
            if (!item.isObject() || item.size() != 3 || !item.path("purpose").isTextual() ||
                    !item.path("decision").isTextual() || !item.path("policyVersion").isTextual() ||
                    !policyVersion.equals(item.path("policyVersion").asText())) throw invalid();
            String purpose = item.path("purpose").asText(), decision = item.path("decision").asText();
            if (!List.of("PRIVATE_COLLECTION", "EXTERNAL_AI").contains(purpose) ||
                    !List.of("GRANTED", "DENIED").contains(decision) ||
                    result.stream().anyMatch(c -> c.purpose().equals(purpose))) throw invalid();
            result.add(new Projects.Consent(purpose, decision, policyVersion));
        }
        if (result.stream().noneMatch(c -> c.purpose().equals("PRIVATE_COLLECTION") && c.decision().equals("GRANTED")))
            throw new ApiProblem(HttpStatus.FORBIDDEN, "private_collection_consent_required");
        return result.stream().sorted(Comparator.comparing(Projects.Consent::purpose)).toList();
    }

    private List<Projects.Repository> fetch(UUID owner, List<Input> inputs) {
        var result = new ArrayList<Projects.Repository>(inputs.size());
        for (Input input : inputs) {
            if (input.id() == null) {
                var repository = github.fetch(input.url().owner(), input.url().repo());
                result.add(new Projects.Repository(repository, null, List.of()));
            } else {
                var privateRepository = connections.privateRepository(owner, input.id());
                var repository = privateRepository.repository();
                result.add(new Projects.Repository(new GitHubRepositoryClient.Repository(
                        input.id(), repository.fullName(), repository.name()), privateRepository.connectionId(), input.consents()));
            }
        }
        return result;
    }

    private static String checkKey(String key) {
        if (key == null) return null;
        if (key.isBlank() || key.length() > 128 || !key.matches("[A-Za-z0-9._:-]+"))
            throw invalid();
        return key;
    }

    private static String fingerprint(String label, List<Input> inputs) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(label.getBytes(StandardCharsets.UTF_8));
            for (var input : inputs) {
                digest.update((byte) 0);
                if (input.id() == null) {
                    digest.update(input.url().owner().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '/');
                    digest.update(input.url().repo().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
                } else {
                    digest.update(("PRIVATE:" + input.id()).getBytes(StandardCharsets.UTF_8));
                    for (var consent : input.consents())
                        digest.update(("|" + consent.purpose() + ":" + consent.decision() + ":" +
                                consent.policyVersion()).getBytes(StandardCharsets.UTF_8));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiProblem invalid() { return new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_request"); }
}
