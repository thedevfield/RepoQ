package dev.devfield.repoq.project.github;

import dev.devfield.repoq.project.api.ApiProblem;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GitHubConnections {
    private static final Logger log = LoggerFactory.getLogger(GitHubConnections.class);
    public record Start(String nextAction, String url, String proof, Instant expiresAt) {}
    public record Status(String userAuthorization, String installation) {}
    public record Listing(String status, java.util.List<GitHubAppClient.Repository> repositories, int page, boolean hasNext) {}
    private record Connection(UUID id, Long githubId, String userState, String installationState, Long installationId,
                              String access, String refresh, Instant accessExpiry, Instant refreshExpiry, Integer keyVersion) {}
    private record Attempt(UUID id, UUID user, UUID session, String kind, String verifier, Integer keyVersion) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final GitHubAppClient github;
    private final TokenCipher cipher;
    private final SecureRandom random = new SecureRandom();
    private final String clientId;
    private final String callback;
    private final String setup;
    private final String install;
    private final long appId;
    private final long ttl;
    private final long skew;

    public GitHubConnections(JdbcTemplate db, TransactionTemplate tx, GitHubAppClient github, TokenCipher cipher,
            @Value("${devfield.project.github.client-id:}") String clientId,
            @Value("${devfield.project.github.callback-url:}") String callback,
            @Value("${devfield.project.github.setup-url:}") String setup,
            @Value("${devfield.project.github.install-url:}") String install,
            @Value("${devfield.project.github.app-id:0}") long appId,
            @Value("${devfield.project.github.attempt-ttl-seconds:600}") long ttl,
            @Value("${devfield.project.github.user-token-refresh-skew-seconds:120}") long skew) {
        this.db = db; this.tx = tx; this.github = github; this.cipher = cipher;
        this.clientId = clientId; this.callback = callback; this.setup = setup; this.install = install;
        this.appId = appId; this.ttl = ttl; this.skew = skew;
        if (ttl < 60 || ttl > 1800 || skew < 0 || skew > 3600) throw new IllegalArgumentException("Invalid GitHub lifecycle settings");
    }

    public Start start(UUID user, UUID session) {
        configured();
        cleanupExpiredAttempts();
        Connection current = connection(user, false);
        String kind = current == null || !"AUTHORIZED".equals(current.userState()) ? "AUTHORIZE_USER" : "INSTALL_APP";
        String proof = randomUrl(32);
        UUID id = UUID.randomUUID();
        String verifier = kind.equals("AUTHORIZE_USER") ? randomUrl(32) : null;
        Instant expiry = Instant.now().plusSeconds(ttl);
        db.update("INSERT INTO project.github_connection_attempt " +
                "(id,devfield_user_id,devfield_session_id,state_hash,kind,pkce_verifier_ciphertext,key_version,expires_at) " +
                "VALUES (?,?,?,?,?,?,?,?)", id, user, session, hash(proof), kind,
                verifier == null ? null : cipher.encrypt(verifier, "attempt:" + id),
                verifier == null ? null : cipher.version(), java.sql.Timestamp.from(expiry));
        String url = kind.equals("AUTHORIZE_USER")
                ? "https://github.com/login/oauth/authorize?client_id=" + enc(clientId) + "&redirect_uri=" + enc(callback) +
                "&state=" + enc(proof) + "&code_challenge=" + enc(Base64.getUrlEncoder().withoutPadding().encodeToString(hash(verifier))) +
                "&code_challenge_method=S256"
                : install;
        return new Start(kind, url, proof, expiry);
    }

    @Scheduled(fixedDelayString = "PT1H")
    public void cleanupExpiredAttempts() {
        db.update("DELETE FROM project.github_connection_attempt WHERE expires_at < now() - interval '1 day'");
    }

    public Status callback(UUID user, UUID session, String code, String proof) {
        configured();
        if (code == null || code.isBlank() || code.length() > 512) throw invalid();
        Attempt attempt;
        try { attempt = consume(user, session, proof, "AUTHORIZE_USER"); }
        catch (ApiProblem e) { log.warn("GitHub App callback attempt validation failed: {}", e.code()); throw e; }
        String verifier = cipher.decrypt(attempt.verifier(), attempt.keyVersion(), "attempt:" + attempt.id());
        GitHubAppClient.Tokens tokens;
        try { tokens = github.exchange(code, verifier, callback); }
        catch (ApiProblem e) { log.warn("GitHub App callback token exchange failed: {}", e.code()); throw e; }
        long githubId;
        try { githubId = github.userId(tokens.access()); }
        catch (ApiProblem e) { log.warn("GitHub App callback user verification failed: {}", e.code()); throw e; }
        try {
            tx.executeWithoutResult(s -> {
                db.update("INSERT INTO project.github_connection (id,devfield_user_id) VALUES (?,?) ON CONFLICT (devfield_user_id) DO NOTHING",
                        UUID.randomUUID(), user);
                Connection existing = connection(user, true);
                if (existing.githubId() != null && existing.githubId() != githubId)
                    throw new ApiProblem(HttpStatus.CONFLICT, "github_account_conflict");
                db.update("UPDATE project.github_connection SET github_user_id=?,user_state='AUTHORIZED'," +
                        "installation_state=CASE WHEN github_user_id=? THEN installation_state ELSE 'NOT_INSTALLED' END," +
                        "installation_id=CASE WHEN github_user_id=? THEN installation_id ELSE NULL END," +
                        "access_token_ciphertext=?,refresh_token_ciphertext=?,token_expires_at=?,refresh_expires_at=?," +
                        "token_key_version=?,updated_at=now() WHERE id=?", githubId, githubId, githubId,
                        cipher.encrypt(tokens.access(), "access:" + existing.id()),
                        cipher.encrypt(tokens.refresh(), "refresh:" + existing.id()),
                        java.sql.Timestamp.from(tokens.accessExpiry()), java.sql.Timestamp.from(tokens.refreshExpiry()),
                        cipher.version(), existing.id());
            });
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ApiProblem(HttpStatus.CONFLICT, "github_account_conflict");
        }
        return status(user);
    }

    public Status setup(UUID user, UUID session, String proof, long installationId, String action) {
        configured();
        if (installationId < 1 || action == null || !java.util.Set.of("install", "update").contains(action)) throw invalid();
        consume(user, session, proof, "INSTALL_APP");
        String token = validToken(user);
        boolean accessible;
        try {
            if (github.userId(token) != connection(user, false).githubId())
                throw new ApiProblem(HttpStatus.FORBIDDEN, "github_identity_mismatch");
            accessible = github.installationAccessible(token, installationId, appId);
            if (accessible) github.repositories(token, installationId, 1, 1);
        } catch (ApiProblem e) {
            if (e.code().equals("github_reauthorization_required")) revokeUserToken(user);
            throw e;
        }
        db.update("UPDATE project.github_connection SET installation_state=?,installation_id=?,updated_at=now() " +
                "WHERE devfield_user_id=? AND user_state='AUTHORIZED'",
                accessible ? "INSTALLED" : "PENDING_APPROVAL", accessible ? installationId : null, user);
        return status(user);
    }

    public Status status(UUID user) {
        Connection c = connection(user, false);
        return c == null ? new Status("NOT_AUTHORIZED", "NOT_INSTALLED") : new Status(c.userState(), c.installationState());
    }

    public Listing repositories(UUID user, int page, int size) {
        configured();
        if (page < 1 || page > 10000 || size < 1 || size > 100) throw invalid();
        Connection c = connection(user, false);
        if (c == null || !"AUTHORIZED".equals(c.userState()))
            return new Listing("USER_AUTHORIZATION_REQUIRED", java.util.List.of(), page, false);
        if (!"INSTALLED".equals(c.installationState()) || c.installationId() == null)
            return new Listing("PENDING_APPROVAL".equals(c.installationState()) ? "INSTALLATION_PENDING" : "INSTALLATION_REQUIRED",
                    java.util.List.of(), page, false);
        String token = validToken(user);
        try {
            if (github.userId(token) != c.githubId())
                throw new ApiProblem(HttpStatus.FORBIDDEN, "github_identity_mismatch");
            if (!github.installationAccessible(token, c.installationId(), appId)) {
                db.update("UPDATE project.github_connection SET installation_state='PENDING_APPROVAL',installation_id=NULL WHERE devfield_user_id=?", user);
                return new Listing("INSTALLATION_PENDING", java.util.List.of(), page, false);
            }
            var result = github.repositories(token, c.installationId(), page, size);
            return new Listing("CONNECTED", result.repositories(), page, result.hasNext());
        } catch (ApiProblem e) {
            if (e.code().equals("github_reauthorization_required")) revokeUserToken(user);
            throw e;
        }
    }

    private void revokeUserToken(UUID user) {
        db.update("UPDATE project.github_connection SET user_state='REAUTHORIZE',installation_state='NOT_INSTALLED'," +
                "installation_id=NULL,access_token_ciphertext=NULL,refresh_token_ciphertext=NULL," +
                "token_expires_at=NULL,refresh_expires_at=NULL,token_key_version=NULL,updated_at=now() " +
                "WHERE devfield_user_id=?", user);
    }

    private String validToken(UUID user) {
        String result = tx.execute(s -> {
            Connection c = connection(user, true);
            if (c == null || !"AUTHORIZED".equals(c.userState())) return null;
            if (c.accessExpiry().isAfter(Instant.now().plusSeconds(skew)))
                return cipher.decrypt(c.access(), c.keyVersion(), "access:" + c.id());
            if (c.refreshExpiry().isAfter(Instant.now().plusSeconds(skew))) {
                try {
                    var fresh = github.refresh(cipher.decrypt(c.refresh(), c.keyVersion(), "refresh:" + c.id()));
                    db.update("UPDATE project.github_connection SET access_token_ciphertext=?,refresh_token_ciphertext=?," +
                            "token_expires_at=?,refresh_expires_at=?,token_key_version=?,updated_at=now() WHERE id=?",
                            cipher.encrypt(fresh.access(), "access:" + c.id()),
                            cipher.encrypt(fresh.refresh(), "refresh:" + c.id()),
                            java.sql.Timestamp.from(fresh.accessExpiry()), java.sql.Timestamp.from(fresh.refreshExpiry()), cipher.version(), c.id());
                    return fresh.access();
                } catch (ApiProblem e) {
                    if (!e.code().equals("github_reauthorization_required")) throw e;
                }
            }
            revokeUserToken(user);
            return null;
        });
        if (result == null) throw new ApiProblem(HttpStatus.UNAUTHORIZED, "github_reauthorization_required");
        return result;
    }

    private Attempt consume(UUID user, UUID session, String proof, String kind) {
        if (proof == null || !proof.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        return tx.execute(s -> {
            var matches = db.query("UPDATE project.github_connection_attempt SET consumed_at=now() " +
                    "WHERE state_hash=? AND devfield_user_id=? AND devfield_session_id=? AND kind=? " +
                    "AND consumed_at IS NULL AND expires_at>now() RETURNING id,devfield_user_id,devfield_session_id,kind,pkce_verifier_ciphertext,key_version",
                    (rs, row) -> new Attempt((UUID)rs.getObject(1), (UUID)rs.getObject(2), (UUID)rs.getObject(3),
                            rs.getString(4), rs.getString(5), (Integer)rs.getObject(6)), hash(proof), user, session, kind);
            if (matches.size() != 1) throw new ApiProblem(HttpStatus.FORBIDDEN, "github_attempt_invalid");
            return matches.getFirst();
        });
    }

    private Connection connection(UUID user, boolean lock) {
        var rows = db.query("SELECT id,github_user_id,user_state,installation_state,installation_id,access_token_ciphertext," +
                "refresh_token_ciphertext,token_expires_at,refresh_expires_at,token_key_version FROM project.github_connection " +
                "WHERE devfield_user_id=?" + (lock ? " FOR UPDATE" : ""), (rs, row) -> new Connection(
                (UUID)rs.getObject(1), (Long)rs.getObject(2), rs.getString(3), rs.getString(4), (Long)rs.getObject(5),
                rs.getString(6), rs.getString(7), rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(),
                rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant(), (Integer)rs.getObject(10)), user);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void configured() {
        try {
            URI callbackUri = URI.create(callback);
            URI setupUri = URI.create(setup);
            URI installUri = URI.create(install);
            if (appId < 1 || clientId.isBlank() || !safeCallback(callbackUri) || !safeCallback(setupUri) ||
                    !"https".equals(installUri.getScheme()) || !"github.com".equals(installUri.getHost()) ||
                    !installUri.getPath().matches("/apps/[A-Za-z0-9-]+/installations/new")) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_configuration_unavailable");
        }
    }

    private static boolean safeCallback(URI uri) {
        return uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null &&
                ("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) &&
                        java.util.Set.of("127.0.0.1", "localhost").contains(uri.getHost())));
    }

    private String randomUrl(int bytes) {
        byte[] data = new byte[bytes]; random.nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
    private static byte[] hash(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static ApiProblem invalid() { return new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_request"); }
}
