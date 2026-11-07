package dev.devfield.repoq.interview.session;

import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
public class InterviewDeletionController {
    public record ProjectDeletion(UUID projectId, UUID ownerUserId, UUID deletionJobId) {}
    public record UserDeletion(UUID userId, UUID deletionJobId, String state) {}

    private final InterviewSessions sessions;

    public InterviewDeletionController(InterviewSessions sessions) {
        this.sessions = sessions;
    }

    @DeleteMapping("/internal/projects/{projectId}/interviews")
    public ResponseEntity<Void> deleteProject(@PathVariable UUID projectId, @RequestBody ProjectDeletion command) {
        if (!projectId.equals(command.projectId()) || command.ownerUserId() == null || command.deletionJobId() == null)
            return ResponseEntity.badRequest().build();
        sessions.deleteProject(command.ownerUserId(), projectId, command.deletionJobId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/internal/users/{userId}/data")
    public ResponseEntity<Void> deleteUser(@PathVariable UUID userId, @RequestBody UserDeletion command) {
        if (!userId.equals(command.userId()) || command.deletionJobId() == null || !"DELETING".equals(command.state()))
            return ResponseEntity.badRequest().build();
        sessions.deleteUser(userId, command.deletionJobId());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Void> badRequest() {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(InterviewSessions.DeletionConflict.class)
    ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "deletion_conflict"));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code", "interview_unavailable"));
    }
}
