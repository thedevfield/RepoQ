package dev.devfield.repoq.interview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfield.repoq.interview.session.InterviewSessionController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class InterviewApplicationTest {
    @Autowired
    JdbcTemplate jdbc;
    @Autowired ApplicationContext context;

    @Test
    void sessionRoutesAreDisabledByDefault() {
        assertTrue(context.getBeansOfType(InterviewSessionController.class).isEmpty());
    }

    @Test
    void connectsToInterviewSchema() {
        assertEquals("interview", jdbc.queryForObject("select current_schema()", String.class));
    }
}
