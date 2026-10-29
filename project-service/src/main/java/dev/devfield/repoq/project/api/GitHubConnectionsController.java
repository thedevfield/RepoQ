package dev.devfield.repoq.project.api;

import dev.devfield.repoq.project.github.GitHubConnections;
import dev.devfield.repoq.project.security.AuthSessionVerifier;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
    private final AuthSessionVerifier sessions;
    private final GitHubConnections connections;

    public GitHubConnectionsController(AuthSessionVerifier sessions, GitHubConnections connections) {
        this.sessions = sessions; this.connections = connections;
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

    private static UUID user(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    private static UUID session(Jwt jwt) {
        try { return UUID.fromString(jwt.getClaimAsString("sid")); }
        catch (RuntimeException e) { throw new ApiProblem(HttpStatus.UNAUTHORIZED, "invalid_session"); }
    }
    private static ApiProblem invalid() { return new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_request"); }
}
