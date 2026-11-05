package dev.devfield.repoq.project.api;

import dev.devfield.repoq.project.github.GitHubConnections;
import dev.devfield.repoq.project.data.Projects;
import dev.devfield.repoq.project.security.AuthSessionVerifier;
import java.util.UUID;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/github")
public class GitHubConnectionsController {
    public record Callback(String code, String proof) {}
    public record Setup(String proof, Long installationId, String setupAction) {}
    public record ConsentInput(String purpose, String decision, String policyVersion) {}
    public record ConsentRequest(List<ConsentInput> consents) {}
    private final AuthSessionVerifier sessions;
    private final GitHubConnections connections;
    private final Projects projects;
    private final String policyVersion;

    public GitHubConnectionsController(AuthSessionVerifier sessions, GitHubConnections connections,
            Projects projects,
            @Value("${devfield.project.github.private-consent-policy-version:}") String policyVersion) {
        this.sessions = sessions; this.connections = connections; this.projects = projects;
        this.policyVersion = policyVersion;
    }

    @PostMapping("/connections/start")
    public GitHubConnections.Start start(@RequestHeader("Authorization") String bearer, @AuthenticationPrincipal Jwt jwt) {
        sessions.verify(bearer);
        return connections.start(user(jwt), session(jwt));
    }

    @PostMapping("/connections/callback/complete")
    public GitHubConnections.Status complete(@RequestHeader("Authorization") String bearer,
            @AuthenticationPrincipal Jwt jwt, @RequestBody Callback input) {
        sessions.verify(bearer);
        if (input == null) throw invalid();
        return connections.callback(user(jwt), session(jwt), input.code(), input.proof());
    }

    @PostMapping("/connections/setup/complete")
    public GitHubConnections.Status setup(@RequestHeader("Authorization") String bearer,
            @AuthenticationPrincipal Jwt jwt, @RequestBody Setup input) {
        sessions.verify(bearer);
        if (input == null || input.installationId() == null) throw invalid();
        return connections.setup(user(jwt), session(jwt), input.proof(), input.installationId(), input.setupAction());
    }

    @GetMapping("/connections")
    public GitHubConnections.Status status(@RequestHeader("Authorization") String bearer, @AuthenticationPrincipal Jwt jwt) {
        sessions.verify(bearer);
        return connections.status(user(jwt));
    }

    @GetMapping("/repositories")
    public GitHubConnections.Listing repositories(@RequestHeader("Authorization") String bearer,
            @AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "30") int perPage) {
        sessions.verify(bearer);
        return connections.repositories(user(jwt), page, perPage);
    }

    @PutMapping("/repositories/{githubRepositoryId}/consents")
    public ResponseEntity<Projects.Revocation> consents(
            @RequestHeader("Authorization") String bearer, @AuthenticationPrincipal Jwt jwt,
            @PathVariable String githubRepositoryId, @RequestBody ConsentRequest input) {
        sessions.verify(bearer);
        if (policyVersion.isBlank() || input == null || input.consents() == null || input.consents().size() != 2 ||
                input.consents().stream().anyMatch(c -> c == null || c.policyVersion() == null ||
                        !policyVersion.equals(c.policyVersion()))) throw invalid();
        var result = projects.changeConsents(user(jwt), githubRepositoryId,
                input.consents().stream().map(c -> new Projects.Consent(
                        c.purpose(), c.decision(), c.policyVersion())).toList());
        return ResponseEntity.status(result.revocationId() == null ? HttpStatus.OK : HttpStatus.ACCEPTED).body(result);
    }

    @GetMapping("/repositories/{githubRepositoryId}/consents")
    public Projects.ConsentStatus consentStatus(@RequestHeader("Authorization") String bearer,
            @AuthenticationPrincipal Jwt jwt, @PathVariable String githubRepositoryId) {
        sessions.verify(bearer);
        return projects.consentStatus(user(jwt), githubRepositoryId);
    }

    @DeleteMapping("/connections/{connectionId}")
    public ResponseEntity<Projects.Disconnection> disconnect(
            @RequestHeader("Authorization") String bearer, @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID connectionId) {
        sessions.verify(bearer);
        return ResponseEntity.accepted().body(projects.disconnect(user(jwt), connectionId));
    }

    private static UUID user(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    private static UUID session(Jwt jwt) {
        try { return UUID.fromString(jwt.getClaimAsString("sid")); }
        catch (RuntimeException e) { throw new ApiProblem(HttpStatus.UNAUTHORIZED, "invalid_session"); }
    }
    private static ApiProblem invalid() { return new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_request"); }
}
