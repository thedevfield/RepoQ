package dev.devfield.repoq.project.security;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtVerificationTest {
    private HttpServer server;
    private RSAKey key;

    @BeforeEach
    void start() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("test-kid").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/jwks", exchange -> {
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void validatesSignatureIssuerAudienceAndRequiredClaims() throws Exception {
        var decoder = new SecurityConfig().jwtDecoder(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/auth/jwks", "issuer", "audience");
        assertNotNull(decoder.decode(token(key, "issuer", "audience", UUID.randomUUID().toString(), 300)));
        assertThrows(JwtException.class, () -> decoder.decode(token(key, "wrong", "audience", UUID.randomUUID().toString(), 300)));
        assertThrows(JwtException.class, () -> decoder.decode(token(key, "issuer", "wrong", UUID.randomUUID().toString(), 300)));
        assertThrows(JwtException.class, () -> decoder.decode(token(key, "issuer", "audience", "bad-sub", 300)));
        assertThrows(JwtException.class, () -> decoder.decode(token(key, "issuer", "audience", UUID.randomUUID().toString(), -10)));
        assertThrows(JwtException.class, () -> decoder.decode(token(key, "issuer", "audience", UUID.randomUUID().toString(), 300, 120)));
        RSAKey other = new RSAKeyGenerator(2048).keyID("test-kid").generate();
        assertThrows(JwtException.class, () -> decoder.decode(token(other, "issuer", "audience", UUID.randomUUID().toString(), 300)));
    }

    private static String token(RSAKey signingKey, String issuer, String audience, String subject, int seconds) throws Exception {
        return token(signingKey, issuer, audience, subject, seconds, -1);
    }

    private static String token(RSAKey signingKey, String issuer, String audience, String subject, int seconds,
                                int notBeforeOffset) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(subject)
                .issueTime(Date.from(now.minusSeconds(1))).notBeforeTime(Date.from(now.plusSeconds(notBeforeOffset)))
                .expirationTime(Date.from(now.plusSeconds(seconds))).jwtID(UUID.randomUUID().toString())
                .claim("sid", UUID.randomUUID().toString()).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID())
                .type(com.nimbusds.jose.JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }
}
