package dev.devfield.repoq.project.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.devfield.repoq.project.data.Projects;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GitHubWebhookController {
    private static final int MAX_BODY = 25 * 1024 * 1024;
    private final byte[] secret;
    private final JdbcTemplate db;
    private final Projects projects;
    private final ObjectMapper json = new ObjectMapper();

    public GitHubWebhookController(@Value("${devfield.project.github.webhook-secret:}") String secret,
                                   JdbcTemplate db, Projects projects) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.db = db;
        this.projects = projects;
    }

    @PostMapping("/api/github/webhook")
    @Transactional
    public ResponseEntity<Void> receive(@RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String delivery,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) throw new ApiProblem(HttpStatus.PAYLOAD_TOO_LARGE, "webhook_too_large");
        if (secret.length == 0 || signature == null || !signature.matches("sha256=[0-9a-fA-F]{64}"))
            throw new ApiProblem(HttpStatus.FORBIDDEN, "invalid_webhook_signature");
        byte[] digest = hmac(body);
        if (!MessageDigest.isEqual(digest, HexFormat.of().parseHex(signature.substring(7))))
            throw new ApiProblem(HttpStatus.FORBIDDEN, "invalid_webhook_signature");
        UUID id;
        try { id = UUID.fromString(delivery); }
        catch (RuntimeException e) { throw new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_webhook_delivery"); }
        if (event == null || !event.matches("[a-z_]{1,64}"))
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_webhook_event");
        byte[] hash;
        try { hash = MessageDigest.getInstance("SHA-256").digest(body); }
        catch (Exception e) { throw new IllegalStateException(e); }
        var prior = db.query("SELECT event_name,action,payload_hash FROM project.github_webhook_delivery " +
                        "WHERE delivery_id=?", (rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getBytes(3)}, id);
        if (!prior.isEmpty()) {
            if (!event.equals(prior.getFirst()[0]) || !MessageDigest.isEqual((byte[]) prior.getFirst()[2], hash))
                throw new ApiProblem(HttpStatus.CONFLICT, "webhook_delivery_conflict");
            return ResponseEntity.noContent().build();
        }
        JsonNode payload;
        try {
            payload = json.readTree(body);
            if (payload == null || !payload.isObject()) throw new IllegalArgumentException();
        } catch (Exception e) { throw new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_webhook_payload"); }
        String action = payload.path("action").asText("");
        if (!action.matches("[a-z_]{0,64}")) throw new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_webhook_action");
        if ("installation".equals(event) && !List.of("created", "deleted", "suspend", "suspended",
                "unsuspend", "unsuspended", "new_permissions_accepted").contains(action) ||
                "installation_repositories".equals(event) && !List.of("added", "removed").contains(action) ||
                "github_app_authorization".equals(event) && !"revoked".equals(action))
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "unsupported_webhook_action");
        int inserted = db.update("INSERT INTO project.github_webhook_delivery(delivery_id,event_name,action,payload_hash) " +
                "VALUES (?,?,?,?) ON CONFLICT (delivery_id) DO NOTHING", id, event, action, hash);
        if (inserted == 0) {
            var previous = db.queryForMap("SELECT event_name,action,payload_hash " +
                    "FROM project.github_webhook_delivery WHERE delivery_id=?", id);
            if (!event.equals(previous.get("event_name")) || !action.equals(previous.get("action")) ||
                    !MessageDigest.isEqual((byte[]) previous.get("payload_hash"), hash))
                throw new ApiProblem(HttpStatus.CONFLICT, "webhook_delivery_conflict");
            return ResponseEntity.noContent().build();
        }
        if ("installation".equals(event) && List.of("deleted", "suspend", "suspended").contains(action)) {
            long installation = positiveId(payload.path("installation").path("id"));
            projects.blockInstallation(installation, null);
        } else if ("installation_repositories".equals(event) &&
                List.of("added", "removed").contains(action)) {
            long installation = positiveId(payload.path("installation").path("id"));
            var removed = payload.path("repositories_removed");
            if (!removed.isArray()) throw new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_webhook_payload");
            var ids = new ArrayList<String>();
            for (JsonNode repository : removed) ids.add(Long.toString(positiveId(repository.path("id"))));
            if (!ids.isEmpty() || "removed".equals(action) ||
                    "selected".equals(payload.path("repository_selection").asText()))
                projects.blockInstallation(installation, ids.isEmpty() ? null : ids);
        } else if ("github_app_authorization".equals(event) && "revoked".equals(action)) {
            projects.blockGitHubUser(positiveId(payload.path("sender").path("id")));
        }
        return ResponseEntity.noContent().build();
    }

    private byte[] hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private long positiveId(JsonNode node) {
        if (!node.canConvertToLong() || node.longValue() < 1)
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_webhook_payload");
        return node.longValue();
    }
}
