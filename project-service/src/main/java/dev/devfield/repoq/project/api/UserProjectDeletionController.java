package dev.devfield.repoq.project.api;

import dev.devfield.repoq.project.deletion.UserProjectDeletion;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;

@RestController
public class UserProjectDeletionController {
    public record Command(UUID userId, UUID deletionJobId, String state) {}

    private final UserProjectDeletion deletion;

    public UserProjectDeletionController(UserProjectDeletion deletion) {
        this.deletion = deletion;
    }

    @DeleteMapping("/internal/users/{userId}/data")
    public ResponseEntity<Void> delete(@PathVariable UUID userId, @RequestBody Command command) {
        if (!userId.equals(command.userId()) || command.deletionJobId() == null || !"DELETING".equals(command.state()))
            return ResponseEntity.badRequest().build();
        deletion.delete(userId, command.deletionJobId());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(TransactionException.class)
    ResponseEntity<Void> unavailable() {
        return ResponseEntity.status(503).build();
    }
}
