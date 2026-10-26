package dev.devfield.repoq.project;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ProjectApplicationTest {
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void connectsToProjectSchema() {
        assertEquals("project", jdbc.queryForObject("select current_schema()", String.class));
    }
}
