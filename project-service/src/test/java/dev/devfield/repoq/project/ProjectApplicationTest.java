package dev.devfield.repoq.project;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "devfield.project.auth.issuer=test-issuer",
        "devfield.project.auth.audience=test-audience",
        "devfield.project.auth.jwks-uri=http://127.0.0.1:1/api/auth/jwks",
        "devfield.project.auth.session-check-uri=http://127.0.0.1:1/internal/auth/session-check",
        "devfield.project.auth.service-key=test-service-key"
})
class ProjectApplicationTest {
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void connectsToProjectSchema() {
        assertEquals("project", jdbc.queryForObject("select current_schema()", String.class));
    }
}
