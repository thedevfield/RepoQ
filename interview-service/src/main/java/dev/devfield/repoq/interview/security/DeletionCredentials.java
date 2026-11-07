package dev.devfield.repoq.interview.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class DeletionCredentials {
    private final byte[] projectHash;
    private final byte[] authHash;

    DeletionCredentials(@Value("${devfield.interview.deletion.project-key-sha256}") String project,
                        @Value("${devfield.interview.deletion.auth-key-sha256}") String auth) {
        byte[] projectKey = parse(project);
        byte[] authKey = parse(auth);
        boolean shared = projectKey != null && authKey != null && MessageDigest.isEqual(projectKey, authKey);
        projectHash = shared ? null : projectKey;
        authHash = shared ? null : authKey;
    }

    boolean permitsProject(HttpServletRequest request) {
        return permits(request, "project", projectHash);
    }

    boolean permitsAuth(HttpServletRequest request) {
        return permits(request, "auth", authHash);
    }

    private static boolean permits(HttpServletRequest request, String caller, byte[] expected) {
        if (expected == null || !caller.equals(singleHeader(request, "X-DevField-Service"))) return false;
        String key = singleHeader(request, "X-DevField-Service-Key");
        if (key == null || key.length() < 32 || key.length() > 512) return false;
        try {
            return MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        if (!values.hasMoreElements()) return null;
        String value = values.nextElement();
        return values.hasMoreElements() ? null : value;
    }

    private static byte[] parse(String hash) {
        return hash.matches("[0-9a-fA-F]{64}") ? HexFormat.of().parseHex(hash) : null;
    }
}
