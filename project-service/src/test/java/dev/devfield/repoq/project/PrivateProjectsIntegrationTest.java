package dev.devfield.repoq.project;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.devfield.repoq.project.api.ApiProblem;
import dev.devfield.repoq.project.api.GitHubConnectionsController;
import dev.devfield.repoq.project.data.Projects;
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
        "devfield.project.github.private-consent-policy-version=test-v1",
        "devfield.project.github.webhook-secret=test-webhook-secret"
})
class PrivateProjectsIntegrationTest {
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate db;
    @Autowired Projects projects;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean AuthSessionVerifier sessions;
    @MockitoBean GitHubRepositoryClient publicGithub;
    @MockitoBean GitHubConnections connections;
    private final UUID owner = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private UUID deliveryId;
    private static final String PRIVATE = "{\"githubRepositoryId\":\"72\",\"consents\":[" +
            "{\"purpose\":\"PRIVATE_COLLECTION\",\"decision\":\"GRANTED\",\"policyVersion\":\"test-v1\"}," +
            "{\"purpose\":\"EXTERNAL_AI\",\"decision\":\"DENIED\",\"policyVersion\":\"test-v1\"}]}";

    @Test
    void consentStatusIsScopedAndDoesNotExposeRepositoryMetadata() throws Exception {
        prepare();
        String path = "/api/github/repositories/72/consents";
        var empty = call("GET", path, "owner", null, null);
        assertEquals(200, empty.statusCode(), empty.body());
        assertEquals("test-v1", json.readTree(empty.body()).path("currentPolicyVersion").asText());
        assertTrue(json.readTree(empty.body()).path("consentSubmissionAvailable").asBoolean());
        assertEquals(2, json.readTree(empty.body()).path("consents").size());
        assertTrue(json.readTree(empty.body()).path("consents").get(0).path("decision").isNull());
        assertTrue(json.readTree(empty.body()).path("consents").get(1).path("policyVersion").isNull());
        assertFalse(empty.body().contains("org/private"));
        when(connections.requirePrivateRepository(other, "72")).thenThrow(
                new ApiProblem(HttpStatus.FORBIDDEN, "github_access_denied"));
        assertEquals(404, call("GET", path, "other", null, null).statusCode());
        assertEquals(400, call("GET", "/api/github/repositories/bad/consents", "owner", null, null).statusCode());
        when(connections.requirePrivateRepository(owner, "999")).thenThrow(
                new ApiProblem(HttpStatus.FORBIDDEN, "github_access_denied"));
        assertEquals(404, call("GET", "/api/github/repositories/999/consents", "owner", null, null).statusCode());
        when(connections.requirePrivateRepository(owner, "998")).thenThrow(
                new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_unavailable"));
        assertEquals(503, call("GET", "/api/github/repositories/998/consents", "owner", null, null).statusCode());
        var unauthenticated = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
        assertEquals(401, http.send(unauthenticated, HttpResponse.BodyHandlers.ofString()).statusCode());
        doThrow(new ApiProblem(HttpStatus.UNAUTHORIZED, "invalid_session")).when(sessions).verify("Bearer owner");
        assertEquals(401, call("GET", path, "owner", null, null).statusCode());
    }

    @Test
    void consentStatusShowsLatestVersionAndRepeatedConsentIsIdempotent() throws Exception {
        prepare();
        db.update("INSERT INTO project.repository_consent(id,owner_user_id,github_repository_id,purpose,decision,policy_version) " +
                "VALUES (?,?,'72','PRIVATE_COLLECTION','GRANTED','test-v0')", UUID.randomUUID(), owner);
        var before = call("GET", "/api/github/repositories/72/consents", "owner", null, null);
        assertEquals("test-v0", json.readTree(before.body()).path("consents").get(0).path("policyVersion").asText());
        assertTrue(json.readTree(before.body()).path("consents").get(1).path("decision").isNull());
        String request = "{\"consents\":[{\"purpose\":\"PRIVATE_COLLECTION\",\"decision\":\"GRANTED\",\"policyVersion\":\"test-v1\"}," +
                "{\"purpose\":\"EXTERNAL_AI\",\"decision\":\"DENIED\",\"policyVersion\":\"test-v1\"}]}";
        assertEquals(200, call("PUT", "/api/github/repositories/72/consents", "owner", request, null).statusCode());
        assertEquals(200, call("PUT", "/api/github/repositories/72/consents", "owner", request, null).statusCode());
        var after = call("GET", "/api/github/repositories/72/consents", "owner", null, null);
        assertEquals("test-v1", json.readTree(after.body()).path("consents").get(0).path("policyVersion").asText());
        assertEquals("DENIED", json.readTree(after.body()).path("consents").get(1).path("decision").asText());
        assertEquals(3, db.queryForObject("SELECT count(*) FROM project.repository_consent WHERE owner_user_id=?",
                Integer.class, owner));
        when(connections.requirePrivateRepository(owner, "72")).thenThrow(
                new ApiProblem(HttpStatus.FORBIDDEN, "github_access_denied"));
        when(connections.requirePrivateRepository(other, "72")).thenThrow(
                new ApiProblem(HttpStatus.FORBIDDEN, "github_access_denied"));
        assertEquals(200, call("GET", "/api/github/repositories/72/consents", "owner", null, null).statusCode());
        assertEquals(404, call("GET", "/api/github/repositories/72/consents", "other", null, null).statusCode());
        var jwt = new Jwt("owner", Instant.now().minusSeconds(10), Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"), Map.of("sub", owner.toString()));
        var unset = new GitHubConnectionsController(sessions, connections, new Projects(db, connections, ""), "");
        assertNull(unset.consentStatus("Bearer owner", jwt, "72").currentPolicyVersion());
        assertFalse(unset.consentStatus("Bearer owner", jwt, "72").consentSubmissionAvailable());
        assertEquals("invalid_request", assertThrows(ApiProblem.class,
                () -> unset.consents("Bearer owner", jwt, "72",
                        new GitHubConnectionsController.ConsentRequest(List.of(
                                new GitHubConnectionsController.ConsentInput("PRIVATE_COLLECTION", "GRANTED", "test-v1"),
                                new GitHubConnectionsController.ConsentInput("EXTERNAL_AI", "DENIED", "test-v1"))))).code());
    }

    @Test
    void bothPurposesCanBeRevokedWithoutCreatingDuplicateEvents() throws Exception {
        prepare();
        String path = "/api/github/repositories/72/consents";
        String granted = "{\"consents\":[" +
                "{\"purpose\":\"PRIVATE_COLLECTION\",\"decision\":\"GRANTED\",\"policyVersion\":\"test-v1\"}," +
                "{\"purpose\":\"EXTERNAL_AI\",\"decision\":\"GRANTED\",\"policyVersion\":\"test-v1\"}]}";
        assertEquals(200, call("PUT", path, "owner", granted, null).statusCode());
        var bothGranted = json.readTree(call("GET", path, "owner", null, null).body());
        assertEquals("GRANTED", bothGranted.path("consents").get(0).path("decision").asText());
        assertEquals("GRANTED", bothGranted.path("consents").get(1).path("decision").asText());
        String revoked = granted.replace("GRANTED", "REVOKED");
        var first = call("PUT", path, "owner", revoked, null);
        var repeat = call("PUT", path, "owner", revoked, null);
        assertEquals(202, first.statusCode());
        assertEquals(202, repeat.statusCode());
        assertEquals(json.readTree(first.body()).path("revocationId"), json.readTree(repeat.body()).path("revocationId"));
        var bothRevoked = json.readTree(call("GET", path, "owner", null, null).body());
        assertEquals("REVOKED", bothRevoked.path("consents").get(0).path("decision").asText());
        assertEquals("REVOKED", bothRevoked.path("consents").get(1).path("decision").asText());
        assertEquals(4, db.queryForObject("SELECT count(*) FROM project.repository_consent WHERE owner_user_id=?",
                Integer.class, owner));
        db.update("UPDATE project.repository_consent SET cleanup_state='PENDING' " +
                "WHERE owner_user_id=? AND purpose='PRIVATE_COLLECTION' AND decision='REVOKED'", owner);
        assertEquals(409, call("PUT", path, "owner", granted, null).statusCode());
    }

    @Test
    void oldPolicyGrantCannotKeepPrivateProjectAccessible() throws Exception {
        prepare();
        var created = call("POST", "/api/projects", "owner", "{\"repositories\":[" + PRIVATE + "]}", null);
        assertEquals(201, created.statusCode(), created.body());
        String path = "/api/projects/" + json.readTree(created.body()).path("id").asText();
        db.update("UPDATE project.repository_consent SET policy_version='test-v0' " +
                "WHERE owner_user_id=? AND purpose='PRIVATE_COLLECTION'", owner);
        var blocked = call("GET", path, "owner", null, null);
        assertEquals(200, blocked.statusCode());
        assertFalse(blocked.body().contains("org/private"));
        assertFalse(blocked.body().contains("\"githubRepositoryId\":\"72\""));
        assertEquals(200, call("PUT", "/api/github/repositories/72/consents", "owner",
                "{\"consents\":[" +
                "{\"purpose\":\"PRIVATE_COLLECTION\",\"decision\":\"GRANTED\",\"policyVersion\":\"test-v1\"}," +
                "{\"purpose\":\"EXTERNAL_AI\",\"decision\":\"DENIED\",\"policyVersion\":\"test-v1\"}]}" , null).statusCode());
        assertTrue(call("GET", path, "owner", null, null).body().contains("org/private"));
    }

    @AfterEach
    void cleanup() {
        if (deliveryId != null) db.update("DELETE FROM project.github_webhook_delivery WHERE delivery_id=?", deliveryId);
        db.update("DELETE FROM project.analysis_job WHERE project_id IN (SELECT id FROM project.registered_project WHERE owner_user_id=?)", owner);
        db.update("DELETE FROM project.repository_consent WHERE owner_user_id=?", owner);
        db.update("DELETE FROM project.project_repository WHERE owner_user_id=?", owner);
        db.update("DELETE FROM project.project_write_fence WHERE owner_user_id IN (?,?)", owner, other);
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
        doReturn(connectionId).when(connections).requirePrivateRepository(owner, "72");
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
        assertEquals(200, deniedDetail.statusCode());
        assertFalse(deniedDetail.body().contains("org/private"));
        assertFalse(deniedDetail.body().contains("\"githubRepositoryId\":\"72\""));
        var deniedList = call("GET", "/api/projects", "owner", null, null);
        assertEquals(200, deniedList.statusCode());
        assertFalse(deniedList.body().contains("org/private"));
        assertEquals(201, call("POST", "/api/projects", "owner", mixed, "mixed-key").statusCode());
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
        assertEquals(204, call("DELETE", "/api/projects/" + id + "/repositories/" + member,
                "owner", null, null).statusCode());
        var many = new java.util.ArrayList<String>();
        for (int repositoryId = 100; repositoryId < 120; repositoryId++) {
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

    @Test
    void revokeBlocksPrivateButPreservesPublicAndRepeats() throws Exception {
        prepare();
        String mixed = "{\"repositories\":[{\"repositoryUrl\":\"https://github.com/owner/repo\"}," + PRIVATE + "]}";
        var created = call("POST", "/api/projects", "owner", mixed, null);
        assertEquals(201, created.statusCode(), created.body());
        UUID project = UUID.fromString(json.readTree(created.body()).path("id").asText());
        when(connections.requirePrivateRepository(owner, "72")).thenThrow(
                new ApiProblem(HttpStatus.FORBIDDEN, "github_access_denied"));
        String revoked = "{\"consents\":[" +
                "{\"purpose\":\"PRIVATE_COLLECTION\",\"decision\":\"REVOKED\",\"policyVersion\":\"test-v1\"}," +
                "{\"purpose\":\"EXTERNAL_AI\",\"decision\":\"DENIED\",\"policyVersion\":\"test-v1\"}]}";
        var first = call("PUT", "/api/github/repositories/72/consents", "owner", revoked, null);
        assertEquals(202, first.statusCode(), first.body());
        assertEquals(403, call("PUT", "/api/github/repositories/72/consents", "other", revoked, null).statusCode());
        var repeat = call("PUT", "/api/github/repositories/72/consents", "owner", revoked, null);
        assertEquals(202, repeat.statusCode());
        assertEquals(json.readTree(first.body()).path("revocationId"), json.readTree(repeat.body()).path("revocationId"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.repository_consent WHERE owner_user_id=? " +
                "AND purpose='PRIVATE_COLLECTION' AND decision='REVOKED'", Integer.class, owner));
        var status = json.readTree(call("GET", "/api/github/repositories/72/consents", "owner", null, null).body());
        assertEquals("REVOKED", status.path("consents").get(0).path("decision").asText());
        assertEquals("DENIED", status.path("consents").get(1).path("decision").asText());
        assertEquals("BLOCKED", db.queryForObject("SELECT access_state FROM project.project_repository " +
                "WHERE owner_user_id=? AND github_repository_id='72'", String.class, owner));
        var detail = call("GET", "/api/projects/" + project, "owner", null, null);
        assertEquals(200, detail.statusCode());
        assertTrue(detail.body().contains("owner/repo"));
        assertFalse(detail.body().contains("org/private"));
        when(publicGithub.fetch("owner", "repo2")).thenReturn(
                new GitHubRepositoryClient.Repository("124", "owner/repo2", "repo2"));
        assertEquals(201, call("POST", "/api/projects/" + project + "/repositories", "owner",
                "{\"repositories\":[{\"repositoryUrl\":\"https://github.com/owner/repo2\"}]}", null).statusCode());
        doReturn(connectionId).when(connections).requirePrivateRepository(owner, "72");
        String granted = revoked.replace("\"purpose\":\"PRIVATE_COLLECTION\",\"decision\":\"REVOKED\"",
                "\"purpose\":\"PRIVATE_COLLECTION\",\"decision\":\"GRANTED\"");
        assertEquals(200, call("PUT", "/api/github/repositories/72/consents", "owner", granted, null).statusCode());
        assertEquals("ACTIVE", db.queryForObject("SELECT access_state FROM project.project_repository " +
                "WHERE owner_user_id=? AND github_repository_id='72'", String.class, owner));
    }

    @Test
    void disconnectDiscardsTokensAndBlocksPrivate() throws Exception {
        prepare();
        var created = call("POST", "/api/projects", "owner", "{\"repositories\":[" + PRIVATE + "]}", null);
        assertEquals(201, created.statusCode());
        db.update("UPDATE project.github_connection SET user_state='REAUTHORIZE' WHERE id=?", connectionId);
        var first = call("DELETE", "/api/github/connections/" + connectionId, "owner", null, null);
        assertEquals(202, first.statusCode(), first.body());
        assertEquals(202, call("DELETE", "/api/github/connections/" + connectionId, "owner", null, null).statusCode());
        assertEquals("DISCONNECTED", db.queryForObject("SELECT user_state FROM project.github_connection WHERE id=?",
                String.class, connectionId));
        assertEquals(1L, db.queryForObject("SELECT generation FROM project.github_connection WHERE id=?",
                Long.class, connectionId));
        assertEquals(404, call("DELETE", "/api/github/connections/" + connectionId, "other", null, null).statusCode());
    }

    @Test
    void signedWebhookIsDeduplicatedAndBlocksInstallation() throws Exception {
        prepare();
        assertEquals(201, call("POST", "/api/projects", "owner", "{\"repositories\":[" + PRIVATE + "]}", null).statusCode());
        db.update("UPDATE project.github_connection SET user_state='AUTHORIZED',github_user_id=900," +
                "installation_state='INSTALLED',installation_id=51,access_token_ciphertext='test'," +
                "refresh_token_ciphertext='test',token_expires_at=now()+interval '1 hour'," +
                "refresh_expires_at=now()+interval '1 hour',token_key_version=1 WHERE id=?", connectionId);
        String payload = "{\"action\":\"deleted\",\"installation\":{\"id\":51}}";
        deliveryId = UUID.randomUUID();
        assertEquals(403, webhook(payload, "sha256=" + "0".repeat(64)).statusCode());
        assertEquals(204, webhook(payload, signature(payload)).statusCode());
        assertEquals(204, webhook(payload, signature(payload)).statusCode());
        assertEquals(409, webhook("{}", signature("{}")).statusCode());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.github_webhook_delivery WHERE delivery_id=?",
                Integer.class, deliveryId));
        assertEquals("BLOCKED", db.queryForObject("SELECT access_state FROM project.project_repository " +
                "WHERE owner_user_id=?", String.class, owner));
    }

    @Test
    void runningJobDoesNotPreventRevocationAndDecisionsRollbackTogether() throws Exception {
        prepare();
        assertEquals(201, call("POST", "/api/projects", "owner", "{\"repositories\":[" + PRIVATE + "]}", null).statusCode());
        UUID project = db.queryForObject("SELECT id FROM project.registered_project WHERE owner_user_id=?", UUID.class, owner);
        db.update("UPDATE project.analysis_job SET state='RUNNING',started_at=now() WHERE project_id=?", project);
        String invalid = "{\"consents\":[" +
                "{\"purpose\":\"PRIVATE_COLLECTION\",\"decision\":\"REVOKED\",\"policyVersion\":\"test-v1\"}," +
                "{\"purpose\":\"EXTERNAL_AI\",\"decision\":\"REVOKED\",\"policyVersion\":\"test-v1\"}]}";
        assertEquals(403, call("PUT", "/api/github/repositories/72/consents", "owner", invalid, null).statusCode());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.repository_consent WHERE owner_user_id=? " +
                "AND decision='REVOKED'", Integer.class, owner));
        assertEquals("RUNNING", db.queryForObject("SELECT state FROM project.analysis_job WHERE project_id=?",
                String.class, project));
        String valid = invalid.replace("\"purpose\":\"EXTERNAL_AI\",\"decision\":\"REVOKED\"",
                "\"purpose\":\"EXTERNAL_AI\",\"decision\":\"DENIED\"");
        assertEquals(202, call("PUT", "/api/github/repositories/72/consents", "owner", valid, null).statusCode());
        assertEquals("CANCELLED", db.queryForObject("SELECT state FROM project.analysis_job WHERE project_id=?",
                String.class, project));
        assertEquals(2L, db.queryForObject("SELECT membership_version FROM project.registered_project WHERE id=?",
                Long.class, project));
    }

    @Test
    void selectedRepositoryChangeAndAuthorizationRevocationBlockAccess() throws Exception {
        prepare();
        assertEquals(201, call("POST", "/api/projects", "owner", "{\"repositories\":[" + PRIVATE + "]}", null).statusCode());
        db.update("UPDATE project.github_connection SET user_state='AUTHORIZED',github_user_id=900," +
                "installation_state='INSTALLED',installation_id=51,access_token_ciphertext='test'," +
                "refresh_token_ciphertext='test',token_expires_at=now()+interval '1 hour'," +
                "refresh_expires_at=now()+interval '1 hour',token_key_version=1 WHERE id=?", connectionId);
        deliveryId = UUID.randomUUID();
        String payload = "{\"action\":\"added\",\"installation\":{\"id\":51}," +
                "\"repository_selection\":\"selected\",\"repositories_removed\":[]}";
        assertEquals(204, webhook(payload, signature(payload), "installation_repositories").statusCode());
        assertEquals("BLOCKED", db.queryForObject("SELECT access_state FROM project.project_repository " +
                "WHERE owner_user_id=?", String.class, owner));
        db.update("DELETE FROM project.github_webhook_delivery WHERE delivery_id=?", deliveryId);
        deliveryId = UUID.randomUUID();
        payload = "{\"action\":\"revoked\",\"sender\":{\"id\":900}}";
        assertEquals(204, webhook(payload, signature(payload), "github_app_authorization").statusCode());
        assertEquals("DISCONNECTED", db.queryForObject("SELECT user_state FROM project.github_connection WHERE id=?",
                String.class, connectionId));
        assertNull(db.queryForObject("SELECT access_token_ciphertext FROM project.github_connection WHERE id=?",
                String.class, connectionId));
    }

    @Test
    void invalidSignedDeliveryRollsBackItsRecord() throws Exception {
        prepare();
        deliveryId = UUID.randomUUID();
        String invalid = "{\"action\":\"deleted\",\"installation\":{}}";
        assertEquals(400, webhook(invalid, signature(invalid)).statusCode());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.github_webhook_delivery WHERE delivery_id=?",
                Integer.class, deliveryId));
    }

    @Test
    void externalAiRevocationKeepsCollectionAndBlockedPrivateAllowsPublicRemoval() throws Exception {
        prepare();
        String mixed = "{\"repositories\":[{\"repositoryUrl\":\"https://github.com/owner/repo\"}," + PRIVATE + "]}";
        assertEquals(201, call("POST", "/api/projects", "owner", mixed, null).statusCode());
        UUID project = db.queryForObject("SELECT id FROM project.registered_project WHERE owner_user_id=?", UUID.class, owner);
        String granted = "{\"consents\":[" +
                "{\"purpose\":\"PRIVATE_COLLECTION\",\"decision\":\"GRANTED\",\"policyVersion\":\"test-v1\"}," +
                "{\"purpose\":\"EXTERNAL_AI\",\"decision\":\"GRANTED\",\"policyVersion\":\"test-v1\"}]}";
        assertEquals(200, call("PUT", "/api/github/repositories/72/consents", "owner", granted, null).statusCode());
        String revokeAi = granted.replace("\"purpose\":\"EXTERNAL_AI\",\"decision\":\"GRANTED\"",
                "\"purpose\":\"EXTERNAL_AI\",\"decision\":\"REVOKED\"");
        assertEquals(202, call("PUT", "/api/github/repositories/72/consents", "owner", revokeAi, null).statusCode());
        assertEquals("ACTIVE", db.queryForObject("SELECT access_state FROM project.project_repository " +
                "WHERE owner_user_id=? AND github_repository_id='72'", String.class, owner));
        assertEquals("GRANTED", db.queryForObject("SELECT decision FROM project.repository_consent WHERE owner_user_id=? " +
                "AND purpose='PRIVATE_COLLECTION' ORDER BY event_order DESC LIMIT 1", String.class, owner));
        var status = json.readTree(call("GET", "/api/github/repositories/72/consents", "owner", null, null).body());
        assertEquals("GRANTED", status.path("consents").get(0).path("decision").asText());
        assertEquals("REVOKED", status.path("consents").get(1).path("decision").asText());
        db.update("UPDATE project.project_repository SET access_state='BLOCKED' WHERE owner_user_id=? " +
                "AND github_repository_id='72'", owner);
        UUID publicMember = db.queryForObject("SELECT id FROM project.project_repository WHERE owner_user_id=? " +
                "AND visibility='PUBLIC'", UUID.class, owner);
        assertEquals(204, call("DELETE", "/api/projects/" + project + "/repositories/" + publicMember,
                "owner", null, null).statusCode());
    }

    private HttpResponse<String> webhook(String body, String signature) throws Exception {
        return webhook(body, signature, "installation");
    }

    private HttpResponse<String> webhook(String body, String signature, String event) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/github/webhook"))
                .header("X-Hub-Signature-256", signature).header("X-GitHub-Delivery", deliveryId.toString())
                .header("X-GitHub-Event", event).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String signature(String body) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("test-webhook-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "HmacSHA256"));
        return "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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
