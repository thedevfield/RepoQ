package dev.devfield.repoq.interview.security;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;

class InterviewAuthenticationTest {
    private static final String AUTH_KEY = "auth-interview-key-for-loopback-tests-only";
    private static final String ISSUER = "devfield-test";
    private static final String AUDIENCE = "repoq-test";
    private HttpServer server;
    private RSAKey key;
    private InterviewAuthentication authentication;
    private volatile int authStatus = 204;
    private final AtomicBoolean validHeaders = new AtomicBoolean();

    @BeforeEach void start() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("test").generate();
        byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                exchange.getResponseBody().write(jwks);
            }
        });
        server.createContext("/internal/auth/session-check", exchange -> {
            try (exchange) {
                validHeaders.set(exchange.getRequestMethod().equals("POST") &&
                        "interview".equals(exchange.getRequestHeaders().getFirst("X-DevField-Service")) &&
                        AUTH_KEY.equals(exchange.getRequestHeaders().getFirst("X-DevField-Service-Key")) &&
                        exchange.getRequestHeaders().getFirst("Authorization").startsWith("Bearer "));
                exchange.sendResponseHeaders(authStatus, -1);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        authentication = new InterviewAuthentication(new SecurityConfig().jwtDecoder(base + "/jwks", ISSUER, AUDIENCE),
                base + "/internal/auth/session-check", AUTH_KEY);
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void acceptsSignedJwtAndActiveSession() throws Exception {
        authentication.verify(token(ISSUER, AUDIENCE, true, true));
        assertTrue(validHeaders.get());
    }

    @Test void invalidClaimsAndExpiredTokenFailBeforeAuthRequest() throws Exception {
        for (String bearer : new String[] { token("wrong", AUDIENCE, true, true), token(ISSUER, "wrong", true, true),
                token(ISSUER, AUDIENCE, false, true), token(ISSUER, AUDIENCE, true, false) }) {
            assertRejected(InterviewAuthentication.Failure.INVALID_SESSION, bearer);
        }
        assertFalse(validHeaders.get());
    }

    @Test void authRejectsRevokedSessionAndUnavailableAccountOrService() throws Exception {
        String bearer = token(ISSUER, AUDIENCE, true, true);
        authStatus = 401;
        assertRejected(InterviewAuthentication.Failure.INVALID_SESSION, bearer);
        authStatus = 403;
        assertRejected(InterviewAuthentication.Failure.ACCOUNT_UNAVAILABLE, bearer);
        authStatus = 503;
        assertRejected(InterviewAuthentication.Failure.AUTH_UNAVAILABLE, bearer);
    }

    private void assertRejected(InterviewAuthentication.Failure expected, String bearer) {
        var error = assertThrows(InterviewAuthentication.Rejected.class, () -> authentication.verify(bearer));
        assertEquals(expected, error.failure());
        assertNull(error.getCause());
    }

    private String token(String issuer, String audience, boolean validSid, boolean unexpired) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(UUID.randomUUID().toString())
                .claim("sid", validSid ? UUID.randomUUID().toString() : "bad").jwtID("test")
                .issueTime(Date.from(now.minusSeconds(600))).notBeforeTime(Date.from(now.minusSeconds(600)))
                .expirationTime(Date.from(now.plusSeconds(unexpired ? 300 : -300))).build();
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID("test").build(), claims);
        token.sign(new RSASSASigner(key));
        return "Bearer " + token.serialize();
    }
}
