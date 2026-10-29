package dev.devfield.repoq.project.github;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.devfield.repoq.project.api.ApiProblem;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@TestPropertySource(properties = {
        "devfield.project.auth.issuer=test-issuer",
        "devfield.project.auth.audience=test-audience",
        "devfield.project.auth.jwks-uri=http://127.0.0.1:1/api/auth/jwks",
        "devfield.project.auth.session-check-uri=http://127.0.0.1:1/internal/auth/session-check",
        "devfield.project.auth.service-key=test-service-key",
        "devfield.project.github.app-id=345",
        "devfield.project.github.client-id=test-client",
        "devfield.project.github.callback-url=http://127.0.0.1:18083/api/github/connections/callback",
        "devfield.project.github.setup-url=http://127.0.0.1:18083/api/github/connections/setup",
        "devfield.project.github.install-url=https://github.com/apps/devfield-test/installations/new",
        "devfield.project.github.token-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "devfield.project.github.user-token-refresh-skew-seconds=0"
})
class GitHubConnectionsIntegrationTest {
    @Autowired GitHubConnections service;
    @Autowired JdbcTemplate db;
    @Autowired TokenCipher cipher;
    @MockitoBean GitHubAppClient github;
    UUID user = UUID.randomUUID();
    UUID session = UUID.randomUUID();
    UUID other = UUID.randomUUID();

    @AfterEach
    void cleanup() {
        db.update("DELETE FROM project.github_connection_attempt WHERE devfield_user_id IN (?,?)", user, other);
        db.update("DELETE FROM project.github_connection WHERE devfield_user_id IN (?,?)", user, other);
    }

    @Test
    void stateSessionPkceAndReplay() {
        var start = service.start(user, session);
        assertEquals("AUTHORIZE_USER", start.nextAction());
        assertTrue(start.url().contains("code_challenge_method=S256"));
        assertTrue(start.url().contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A18083%2Fapi%2Fgithub%2Fconnections%2Fcallback"));
        assertFalse(start.url().contains("code_verifier"));
        var attempt = db.queryForMap("SELECT id,pkce_verifier_ciphertext,key_version FROM project.github_connection_attempt WHERE state_hash=?", sha(start.proof()));
        String verifier = cipher.decrypt((String)attempt.get("pkce_verifier_ciphertext"),
                (Integer)attempt.get("key_version"), "attempt:" + attempt.get("id"));
        String challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sha(verifier));
        assertTrue(start.url().contains("code_challenge=" + challenge));
        assertNotEquals(verifier, attempt.get("pkce_verifier_ciphertext"));
        assertEquals("github_attempt_invalid", assertThrows(ApiProblem.class,
                () -> service.callback(user, UUID.randomUUID(), "code", start.proof())).code());
        assertEquals("github_attempt_invalid", assertThrows(ApiProblem.class,
                () -> service.callback(user, session, "code", "A".repeat(43))).code());
        db.update("UPDATE project.github_connection_attempt SET created_at=now()-interval '2 seconds'," +
                "expires_at=now()-interval '1 second' WHERE state_hash=?",
                sha(start.proof()));
        assertEquals("github_attempt_invalid", assertThrows(ApiProblem.class,
                () -> service.callback(user, session, "code", start.proof())).code());

        var valid = service.start(user, session);
        when(github.exchange(eq("code"), anyString(), anyString())).thenReturn(tokens());
        when(github.userId("access-value")).thenReturn(900L);
        assertEquals("AUTHORIZED", service.callback(user, session, "code", valid.proof()).userAuthorization());
        verify(github).exchange(eq("code"), argThat(v -> v.length() == 43),
                eq("http://127.0.0.1:18083/api/github/connections/callback"));
        assertEquals("github_attempt_invalid", assertThrows(ApiProblem.class,
                () -> service.callback(user, session, "code", valid.proof())).code());
        String stored = db.queryForObject("SELECT access_token_ciphertext FROM project.github_connection WHERE devfield_user_id=?", String.class, user);
        assertNotEquals("access-value", stored);
        assertFalse(stored.contains("access-value"));
    }

    @Test
    void installationProofAndOwnerIsolation() {
        authorize();
        var install = service.start(user, session);
        assertEquals("INSTALL_APP", install.nextAction());
        assertEquals("github_attempt_invalid", assertThrows(ApiProblem.class,
                () -> service.setup(user, UUID.randomUUID(), install.proof(), 51, "install")).code());
        when(github.installationAccessible("access-value", 51, 345)).thenReturn(false);
        assertEquals("PENDING_APPROVAL", service.setup(user, session, install.proof(), 51, "install").installation());
        assertEquals("github_attempt_invalid", assertThrows(ApiProblem.class,
                () -> service.setup(user, session, install.proof(), 51, "install")).code());
        assertEquals("INSTALLATION_PENDING", service.repositories(user, 1, 30).status());
        assertEquals("USER_AUTHORIZATION_REQUIRED", service.repositories(other, 1, 30).status());

        var approved = service.start(user, session);
        when(github.installationAccessible("access-value", 52, 345)).thenReturn(true);
        when(github.repositories("access-value", 52, 1, 1)).thenReturn(new GitHubAppClient.RepositoryPage(List.of(), false));
        assertEquals("INSTALLED", service.setup(user, session, approved.proof(), 52, "install").installation());
        when(github.repositories("access-value", 52, 2, 20)).thenReturn(new GitHubAppClient.RepositoryPage(
                List.of(new GitHubAppClient.Repository(72, "org/private", "private", true)), false));
        var list = service.repositories(user, 2, 20);
        assertEquals("CONNECTED", list.status());
        assertEquals(72, list.repositories().getFirst().id());
        assertTrue(service.repositories(other, 2, 20).repositories().isEmpty());
    }

    @Test
    void refreshSerializesAndRotatesCiphertext() throws Exception {
        authorize();
        db.update("UPDATE project.github_connection SET token_expires_at=now()-interval '1 second' WHERE devfield_user_id=?", user);
        when(github.refresh("refresh-value")).thenReturn(new GitHubAppClient.Tokens("new-access", "new-refresh",
                Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200)));
        when(github.userId("new-access")).thenReturn(900L);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> service.repositories(user, 1, 10));
            var b = pool.submit(() -> service.repositories(user, 1, 10));
            assertEquals("INSTALLATION_REQUIRED", a.get().status());
            assertEquals("INSTALLATION_REQUIRED", b.get().status());
        }
        // Install first, then force refresh through the repository path.
        var install = service.start(user, session);
        when(github.userId("new-access")).thenReturn(900L);
        when(github.installationAccessible("new-access", 88, 345)).thenReturn(true);
        when(github.repositories("new-access", 88, 1, 1)).thenReturn(new GitHubAppClient.RepositoryPage(List.of(), false));
        service.setup(user, session, install.proof(), 88, "install");
        db.update("UPDATE project.github_connection SET token_expires_at=now()-interval '1 second' WHERE devfield_user_id=?", user);
        when(github.refresh("new-refresh")).thenReturn(new GitHubAppClient.Tokens("third-access", "third-refresh",
                Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200)));
        when(github.userId("third-access")).thenReturn(900L);
        when(github.installationAccessible("third-access", 88, 345)).thenReturn(true);
        when(github.repositories("third-access", 88, 1, 10)).thenReturn(new GitHubAppClient.RepositoryPage(List.of(), false));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> service.repositories(user, 1, 10));
            var b = pool.submit(() -> service.repositories(user, 1, 10));
            assertEquals("CONNECTED", a.get().status());
            assertEquals("CONNECTED", b.get().status());
        }
        verify(github, times(1)).refresh("new-refresh");
        UUID id = db.queryForObject("SELECT id FROM project.github_connection WHERE devfield_user_id=?", UUID.class, user);
        String encrypted = db.queryForObject("SELECT refresh_token_ciphertext FROM project.github_connection WHERE id=?", String.class, id);
        assertEquals("third-refresh", cipher.decrypt(encrypted, cipher.version(), "refresh:" + id));
    }

    @Test
    void failedRefreshClearsCredentialsAndApiOutageFailsClosed() {
        authorize();
        var install = service.start(user, session);
        when(github.installationAccessible("access-value", 88, 345)).thenReturn(true);
        when(github.repositories("access-value", 88, 1, 1)).thenReturn(new GitHubAppClient.RepositoryPage(List.of(), false));
        service.setup(user, session, install.proof(), 88, "install");
        when(github.repositories("access-value", 88, 1, 30)).thenThrow(
                new ApiProblem(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "github_rate_limited"));
        assertEquals("github_rate_limited", assertThrows(ApiProblem.class,
                () -> service.repositories(user, 1, 30)).code());
        db.update("UPDATE project.github_connection SET token_expires_at=now()-interval '1 second' WHERE devfield_user_id=?", user);
        when(github.refresh("refresh-value")).thenThrow(
                new ApiProblem(org.springframework.http.HttpStatus.UNAUTHORIZED, "github_reauthorization_required"));
        assertEquals("github_reauthorization_required", assertThrows(ApiProblem.class,
                () -> service.repositories(user, 1, 30)).code());
        assertEquals("REAUTHORIZE", service.status(user).userAuthorization());
        assertNull(db.queryForObject("SELECT access_token_ciphertext FROM project.github_connection WHERE devfield_user_id=?",
                String.class, user));
    }

    @Test
    void githubAccountCannotBeBoundToAnotherDevfieldUser() {
        authorize();
        UUID otherSession = UUID.randomUUID();
        var start = service.start(other, otherSession);
        when(github.exchange(eq("second"), anyString(), anyString())).thenReturn(tokens());
        assertEquals("github_account_conflict", assertThrows(ApiProblem.class,
                () -> service.callback(other, otherSession, "second", start.proof())).code());
    }

    private void authorize() {
        var start = service.start(user, session);
        when(github.exchange(eq("code"), anyString(), anyString())).thenReturn(tokens());
        when(github.userId("access-value")).thenReturn(900L);
        service.callback(user, session, "code", start.proof());
    }

    private static GitHubAppClient.Tokens tokens() {
        return new GitHubAppClient.Tokens("access-value", "refresh-value", Instant.now().plusSeconds(3600),
                Instant.now().plusSeconds(7200));
    }
    private static byte[] sha(String value) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII)); }
        catch (Exception e) { throw new AssertionError(e); }
    }
}
