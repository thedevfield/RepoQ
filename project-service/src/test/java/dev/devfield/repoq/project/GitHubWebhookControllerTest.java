package dev.devfield.repoq.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.devfield.repoq.project.api.ApiProblem;
import dev.devfield.repoq.project.api.GitHubWebhookController;
import dev.devfield.repoq.project.data.Projects;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

class GitHubWebhookControllerTest {
    @Test
    void missingSecretAndOversizedBodyNeverTouchDatabase() throws Exception {
        JdbcTemplate db = mock(JdbcTemplate.class);
        Projects projects = mock(Projects.class);
        var unsigned = new MockHttpServletRequest();
        unsigned.setContent("{}".getBytes());
        var missing = new GitHubWebhookController("", db, projects);
        assertEquals("invalid_webhook_signature", assertThrows(ApiProblem.class,
                () -> missing.receive("sha256=" + "0".repeat(64), "00000000-0000-0000-0000-000000000001",
                        "installation", unsigned)).code());
        var oversized = new MockHttpServletRequest();
        oversized.setContent(new byte[25 * 1024 * 1024 + 1]);
        var configured = new GitHubWebhookController("test-secret", db, projects);
        assertEquals("webhook_too_large", assertThrows(ApiProblem.class,
                () -> configured.receive(null, "00000000-0000-0000-0000-000000000001",
                        "installation", oversized)).code());
        verifyNoInteractions(db, projects);
    }
}
