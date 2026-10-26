package dev.devfield.repoq.interview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class InterviewApplicationTest {
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void connectsToInterviewSchema() {
        assertEquals("interview", jdbc.queryForObject("select current_schema()", String.class));
    }
}
