package dev.devfield.repoq.project.api;

import dev.devfield.repoq.project.analysis.ProjectAnalysisReads;
import dev.devfield.repoq.project.security.AuthSessionVerifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class InterviewDataController {
    private final ProjectAnalysisReads reads;
    private final AuthSessionVerifier sessions;
    private final byte[] keyHash;

    public InterviewDataController(ProjectAnalysisReads reads, AuthSessionVerifier sessions,
                                   @Value("${devfield.project.interview-key-sha256:}") String configuredHash) {
        this.reads = reads;
        this.sessions = sessions;
        this.keyHash = configuredHash.matches("[0-9a-fA-F]{64}") ? HexFormat.of().parseHex(configuredHash) : null;
    }

    @GetMapping("/internal/projects/{projectId}/interview-data")
    public ResponseEntity<ProjectAnalysisReads.InterviewData> get(
            @PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
            @RequestHeader("Authorization") String bearer) {
        String caller = request.getHeader("X-DevField-Service");
        String key = request.getHeader("X-DevField-Service-Key");
        if (keyHash == null || !"interview".equals(caller) || key == null || key.length() < 32 || key.length() > 512 ||
                !MessageDigest.isEqual(keyHash, sha256(key))) {
            throw new ApiProblem(HttpStatus.FORBIDDEN, "forbidden");
        }
        sessions.verify(bearer);
        String[] scope = request.getParameterValues("evidenceScope");
        if (request.getParameterMap().size() > (scope == null ? 0 : 1) || scope != null && scope.length != 1)
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_request");
        if (scope != null && !"PUBLIC_ONLY".equals(scope[0])) {
            if ("FULL".equals(scope[0])) throw new ApiProblem(HttpStatus.FORBIDDEN, "evidence_scope_not_supported");
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_request");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(reads.interviewData(UUID.fromString(jwt.getSubject()), projectId));
    }

    private static byte[] sha256(String key) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
