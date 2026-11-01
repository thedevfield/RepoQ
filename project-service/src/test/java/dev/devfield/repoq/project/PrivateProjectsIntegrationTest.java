package dev.devfield.repoq.project;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.devfield.repoq.project.api.ApiProblem;
import dev.devfield.repoq.project.github.GitHubAppClient;
import dev.devfield.repoq.project.github.GitHubConnections;
import dev.devfield.repoq.project.github.GitHubRepositoryClient;
import dev.devfield.repoq.project.security.AuthSessionVerifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "devfield.project.auth.issuer=test-issuer",
        "devfield.project.auth.audience=test-audience",
        "devfield.project.auth.jwks-uri=http://127.0.0.1:1/api/auth/jwks",
        "devfield.project.auth.session-check-uri=http://127.0.0.1:1/internal/auth/session-check",
        "devfield.project.auth.service-key=test-service-key",
        "devfield.project.github.private-registration-enabled=true",
        "devfield.project.github.private-consent-policy-version=test-v1"
})
class PrivateProjectsIntegrationTest {
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate db;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean AuthSessionVerifier sessions;
    @MockitoBean GitHubRepositoryClient publicGithub;
    @MockitoBean GitHubConnections connections;
    private final UUID owner = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private static final String PRIVATE = "{\"githubRepositoryId\":\"72\",\"consents\":[" +
            "{\"purpose\":\"PRIVATE_COLLECTION\",\"decision\":\"GRANTED\",\"policyVersion\":\"test-v1\"}," +
            "{\"purpose\":\"EXTERNAL_AI\",\"decision\":\"DENIED\",\"policyVersion\":\"test-v1\"}]}";

    @AfterEach
    void cleanup() {
        db.update("DELETE FROM project.analysis_job WHERE project_id IN (SELECT id FROM project.registered_project WHERE owner_user_id=?)", owner);
        db.update("DELETE FROM project.repository_consent WHERE owner_user_id=?", owner);
        db.update("DELETE FROM project.project_repository WHERE owner_user_id=?", owner);
        db.update("DELETE FROM project.project_write_fence WHERE owner_user_id=?", owner);
        db.update("DELETE FROM project.registered_project WHERE owner_user_id=?", owner);
        db.update("DELETE FROM project.github_connection WHERE devfield_user_id=?", owner);
    }

    private void prepare() {
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String token = call.getArgument(0);
            UUID user = token.equals("other") ? other : owner;
            return new Jwt(token, Instant.now().minusSeconds(10), Instant.now().plusSeconds(300),
                    Map.of("alg", "RS256"), Map.of("sub", user.toString(), "aud", List.of("test-audience")));
        });
        db.update("INSERT INTO project.github_connection(id,devfield_user_id) VALUES (?,?)", connectionId, owner);
        when(connections.privateRepository(eq(owner), eq("72"))).thenReturn(new GitHubConnections.PrivateRepository(
                connectionId, new GitHubAppClient.Repository(72, "org/private", "private", true)));
        when(connections.requirePrivateRepository(owner, "72")).thenReturn(connectionId);
        when(connections.privateRepository(eq(owner), eq("73"))).thenReturn(new GitHubConnections.PrivateRepository(
                connectionId, new GitHubAppClient.Repository(73, "org/second", "second", true)));
        when(connections.requirePrivateRepository(owner, "73")).thenReturn(connectionId);
        when(publicGithub.fetch("owner", "repo")).thenReturn(
                new GitHubRepositoryClient.Repository("123", "owner/repo", "repo"));
    }

    @Test
    void privateMixedConsentAndReplayFailClosed() throws Exception {
        prepare();
        String mixed = "{\"repositories\":[{\"repositoryUrl\":\"https://github.com/owner/repo\"}," + PRIVATE + "]}";
        var created = call("POST", "/api/projects", "owner", mixed, "mixed-key");
        assertEquals(201, created.statusCode(), created.body());
        UUID project = UUID.fromString(json.readTree(created.body()).path("id").asText());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM project.repository_consent WHERE owner_user_id=?", Integer.class, owner));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.repository_consent WHERE owner_user_id=? " +
                "AND purpose='EXTERNAL_AI' AND decision='DENIED'", Integer.class, owner));
        assertEquals(201, call("POST", "/api/projects", "owner", mixed, "mixed-key").statusCode());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM project.repository_consent WHERE owner_user_id=?", Integer.class, owner));
        assertEquals(409, call("POST", "/api/projects", "owner", mixed.replace("DENIED", "GRANTED"), "mixed-key").statusCode());
        assertEquals(200, call("GET", "/api/projects/" + project, "owner", null, null).statusCode());
        assertEquals(200, call("GET", "/api/projects", "owner", null, null).statusCode());
        assertEquals(404, call("GET", "/api/projects/" + project, "other", null, null).statusCode());
        when(connections.requirePrivateRepository(owner, "72")).thenThrow(
                new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_unavailable"));
        var deniedDetail = call("GET", "/api/projects/" + project, "owner", null, null);
        assertEquals(503, deniedDetail.statusCode());
        assertFalse(deniedDetail.body().contains("org/private"));
        var deniedList = call("GET", "/api/projects", "owner", null, null);
        assertEquals(503, deniedList.statusCode());
        assertFalse(deniedList.body().contains("org/private"));
        assertEquals(503, call("POST", "/api/projects", "owner", mixed, "mixed-key").statusCode());
    }

    @Test
    void validationAndRollback() throws Exception {
        prepare();
        String request = "{\"repositories\":[" + PRIVATE + "]}";
        assertEquals(403, call("POST", "/api/projects", "owner", request.replace("GRANTED", "DENIED"), null).statusCode());
        assertEquals(400, call("POST", "/api/projects", "owner", request.replace("test-v1", "unsupported"), null).statusCode());
        assertEquals(400, call("POST", "/api/projects", "owner", request.replace("\"githubRepositoryId\":\"72\"",
                "\"githubRepositoryId\":\"72\",\"repositoryUrl\":\"https://github.com/owner/repo\""), null).statusCode());
        when(publicGithub.fetch("owner", "repo")).thenThrow(new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_unavailable"));
        assertEquals(503, call("POST", "/api/projects", "owner", "{\"repositories\":[" + PRIVATE +
                ", {\"repositoryUrl\":\"https://github.com/owner/repo\"}]}", null).statusCode());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.repository_consent WHERE owner_user_id=?", Integer.class, owner));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.registered_project WHERE owner_user_id=?", Integer.class, owner));
    }

    @Test
    void privateOnlyMultipleAndAddition() throws Exception {
        prepare();
        var first = call("POST", "/api/projects", "owner", "{\"repositories\":[" + PRIVATE + "]}", null);
        assertEquals(201, first.statusCode(), first.body());
        UUID id = UUID.fromString(json.readTree(first.body()).path("id").asText());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM project.repository_consent WHERE owner_user_id=?", Integer.class, owner));
        String second = PRIVATE.replace("\"72\"", "\"73\"");
        assertEquals(201, call("POST", "/api/projects/" + id + "/repositories", "owner",
                "{\"repositories\":[" + second + "]}", "add-private").statusCode());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM project.project_repository WHERE owner_user_id=? " +
                "AND visibility='PRIVATE'", Integer.class, owner));
        assertEquals(4, db.queryForObject("SELECT count(*) FROM project.repository_consent WHERE owner_user_id=?", Integer.class, owner));
        assertEquals(201, call("POST", "/api/projects/" + id + "/repositories", "owner",
                "{\"repositories\":[" + second + "]}", "add-private").statusCode());
        assertEquals(4, db.queryForObject("SELECT count(*) FROM project.repository_consent WHERE owner_user_id=?", Integer.class, owner));
        UUID member = db.queryForObject("SELECT id FROM project.project_repository WHERE owner_user_id=? " +
                "AND github_repository_id='73'", UUID.class, owner);
        assertEquals(409, call("DELETE", "/api/projects/" + id + "/repositories/" + member,
                "owner", null, null).statusCode());
        var many = new java.util.ArrayList<String>();
        for (int repositoryId = 100; repositoryId < 119; repositoryId++) {
            String value = Integer.toString(repositoryId);
            when(connections.privateRepository(owner, value)).thenReturn(new GitHubConnections.PrivateRepository(
                    connectionId, new GitHubAppClient.Repository(repositoryId, "org/repo" + value, "repo" + value, true)));
            many.add(PRIVATE.replace("\"72\"", "\"" + value + "\""));
        }
        assertEquals(400, call("POST", "/api/projects/" + id + "/repositories", "owner",
                "{\"repositories\":[" + String.join(",", many) + "]}", null).statusCode());
    }

    @Test
    void dbConflictRollsBackInlineConsent() throws Exception {
        prepare();
        assertEquals(201, call("POST", "/api/projects", "owner",
                "{\"repositoryUrl\":\"https://github.com/owner/repo\"}", null).statusCode());
        String request = "{\"repositories\":[" + PRIVATE +
                ", {\"repositoryUrl\":\"https://github.com/owner/repo\"}]}";
        assertEquals(409, call("POST", "/api/projects", "owner", request, null).statusCode());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.repository_consent WHERE owner_user_id=?", Integer.class, owner));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.registered_project WHERE owner_user_id=?", Integer.class, owner));
    }

    @Test
    void githubDenialAndExpiryRejectPrivateRegistration() throws Exception {
        prepare();
        String request = "{\"repositories\":[" + PRIVATE + "]}";
        for (HttpStatus status : List.of(HttpStatus.FORBIDDEN, HttpStatus.UNAUTHORIZED, HttpStatus.SERVICE_UNAVAILABLE)) {
            doThrow(new ApiProblem(status, "github_access_denied"))
                    .when(connections).privateRepository(owner, "72");
            assertEquals(status.value(), call("POST", "/api/projects", "owner", request, null).statusCode());
            assertEquals(0, db.queryForObject("SELECT count(*) FROM project.repository_consent WHERE owner_user_id=?", Integer.class, owner));
        }
        when(connections.privateRepository(other, "72")).thenThrow(
                new ApiProblem(HttpStatus.FORBIDDEN, "github_access_denied"));
        assertEquals(403, call("POST", "/api/projects", "other", request, null).statusCode());
    }

    private HttpResponse<String> call(String method, String path, String token, String body, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() :
                HttpRequest.BodyPublishers.ofString(body));
        if (body != null) builder.header("Content-Type", "application/json");
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
