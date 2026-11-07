package dev.devfield.repoq.project.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class UserDeletionCredential {
    private final byte[] expected;

    UserDeletionCredential(@Value("${devfield.project.deletion.auth-key-sha256:}") String configured,
                           @Value("${devfield.project.interview-key-sha256:}") String interviewHash,
                           @Value("${devfield.project.deletion.service-key:}") String x02Key,
                           @Value("${devfield.project.auth.service-key:}") String authSessionKey) {
        byte[] hash = configured.matches("[0-9a-fA-F]{64}") ? HexFormat.of().parseHex(configured) : null;
        expected = hash == null || configured.equalsIgnoreCase(interviewHash) ||
                MessageDigest.isEqual(hash, sha256(x02Key)) || MessageDigest.isEqual(hash, sha256(authSessionKey))
                ? null : hash;
    }

    boolean permits(HttpServletRequest request) {
        if (expected == null || !"auth".equals(singleHeader(request, "X-DevField-Service"))) return false;
        String key = singleHeader(request, "X-DevField-Service-Key");
        return key != null && key.length() >= 32 && key.length() <= 512 &&
                MessageDigest.isEqual(expected, sha256(key));
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        if (!values.hasMoreElements()) return null;
        String value = values.nextElement();
        return values.hasMoreElements() ? null : value;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
