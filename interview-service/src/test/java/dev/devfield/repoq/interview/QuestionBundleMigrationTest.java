package dev.devfield.repoq.interview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class QuestionBundleMigrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ResourcePatternResolver resources;

    @Test
    void migratesV5AnswersEvaluationsAndRunningJobWithoutChangingStoredData() throws IOException {
        String schema = "interview_v6_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        for (int version = 1; version <= 5; version++) applyMigration(version, schema);

        executeInSchema(schema, """
                INSERT INTO interview.interview_session
                    (id,owner_user_id,project_id,snapshot_id,membership_version,snapshot_context,mode,state,requested_topic_key)
                VALUES
                    ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000011',
                     '00000000-0000-0000-0000-000000000012','00000000-0000-0000-0000-000000000013',
                     1,'{}','FREE','ACTIVE','JAVA'),
                    ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000011',
                     '00000000-0000-0000-0000-000000000012','00000000-0000-0000-0000-000000000013',
                     1,'{}','FREE','PREPARING','RANDOM');

                INSERT INTO interview.first_question_job
                    (session_id,resolved_topic_key,evidence_id,state,attempt,due_at,lease_until,question_id,question_text)
                VALUES
                    ('00000000-0000-0000-0000-000000000001','JAVA','00000000-0000-0000-0000-000000000021',
                     'READY',1,'2026-11-01T00:00:00Z',NULL,'00000000-0000-0000-0000-000000000031','Legacy question'),
                    ('00000000-0000-0000-0000-000000000002','JAVA','00000000-0000-0000-0000-000000000021',
                     'RUNNING',2,'2026-11-01T00:00:30Z','2026-11-01T00:00:30Z',NULL,NULL);

                INSERT INTO interview.question_turn(session_id,position,id,topic_key,evidence_id,question_text)
                SELECT session_id,0,question_id,resolved_topic_key,evidence_id,question_text
                FROM interview.first_question_job WHERE state='READY';

                INSERT INTO interview.user_answer(session_id,question_id,submission_id,answer_text,state)
                SELECT session_id,id,'legacy-key','Legacy answer','STOPPED' FROM interview.question_turn;

                INSERT INTO interview.bundle_evaluation(session_id,closing_question_id,state,attempt,result)
                SELECT session_id,question_id,'READY',1,
                    '{"evidenceId":"00000000-0000-0000-0000-000000000021","strengths":"Legacy feedback",
                      "gaps":"Discuss alternatives.","improvements":"Compare trade-offs.",
                      "exampleAnswer":"I chose the simpler option.","keyPoints":"Explain why.","limitations":"No benchmark."}'
                FROM interview.user_answer;
                """);

        var originalSessions = rows(schema, "interview_session", "id");
        var originalJobs = rows(schema, "first_question_job", "session_id");
        var originalQuestions = rows(schema, "question_turn", "session_id,position");
        var originalAnswers = rows(schema, "user_answer", "session_id,question_id");
        var originalEvaluations = rows(schema, "bundle_evaluation", "session_id");

        applyMigration(6, schema);

        assertEquals(originalSessions, rows(schema, "interview_session", "id"));
        assertEquals(originalJobs, rows(schema, "first_question_job", "session_id"));
        assertEquals(originalQuestions, rows(schema, "question_turn", "session_id,position"));
        assertEquals(originalAnswers, rows(schema, "user_answer", "session_id,question_id"));
        assertEquals(originalEvaluations, rows(schema, "bundle_evaluation", "session_id"));

        assertEquals(List.of(Map.of("sequence", 1, "requested_topic_key", "JAVA"),
                        Map.of("sequence", 1, "requested_topic_key", "RANDOM")),
                jdbc.queryForList("""
                        SELECT sequence,requested_topic_key FROM %s.question_bundle
                        WHERE id=session_id AND previous_bundle_id IS NULL ORDER BY session_id
                        """.formatted(schema)));
        for (String table : List.of("first_question_job", "question_turn", "bundle_evaluation")) {
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM %s.%s WHERE bundle_id IS DISTINCT FROM session_id".formatted(schema, table),
                    Integer.class), table);
        }
    }

    private void applyMigration(int version, String schema) throws IOException {
        var migration = resources.getResources("classpath:db/interview/V" + version + "__*.sql");
        assertEquals(1, migration.length, "migration V" + version);
        executeInSchema(schema, migration[0].getContentAsString(StandardCharsets.UTF_8));
    }

    private void executeInSchema(String schema, String sql) {
        // Production scripts qualify their tables explicitly. Redirect them into the rollback-only test schema.
        jdbc.execute(sql.replace("interview.", schema + "."));
    }

    private List<String> rows(String schema, String table, String order) {
        return jdbc.queryForList("""
                SELECT (to_jsonb(row_data) - 'bundle_id')::text FROM %s.%s row_data ORDER BY %s
                """.formatted(schema, table, order), String.class);
    }
}
