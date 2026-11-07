package dev.devfield.repoq.project.deletion;

import static org.junit.jupiter.api.Assertions.*;

import dev.devfield.repoq.interview.InterviewApplication;
import dev.devfield.repoq.interview.project.ProjectSnapshot;
import dev.devfield.repoq.interview.session.InterviewSessions;
import dev.devfield.repoq.project.ProjectApplication;
import dev.devfield.repoq.project.data.Projects;
import dev.devfield.repoq.project.github.GitHubRepositoryClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "PROJECT_DB_URL", matches = ".*[/]repoq_analysis_test[^?]*(?:\\?.*)?")
@EnabledIfEnvironmentVariable(named = "INTERVIEW_DB_URL", matches = ".*[/]repoq_analysis_test[^?]*(?:\\?.*)?")
class ProjectDeletionRestartIntegrationTest {
    @Test void schedulerRecoversPersistedDeletionAndCallsRealInterviewAfterRestart() throws Exception {
        String key = UUID.randomUUID().toString();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(StandardCharsets.UTF_8)));
        UUID owner = UUID.randomUUID();

        try (var interview = new SpringApplicationBuilder(InterviewApplication.class).run(
                "--spring.config.location=optional:classpath:/deletion-restart-test-no-config/",
                "--server.address=127.0.0.1", "--server.port=0",
                dbSetting("INTERVIEW", "URL", "url"),
                dbSetting("INTERVIEW", "USER", "username"),
                dbSetting("INTERVIEW", "PASSWORD", "password"),
                "--spring.flyway.default-schema=interview", "--spring.flyway.schemas=interview",
                "--spring.flyway.locations=classpath:db/interview",
                "--devfield.interview.auth.issuer=", "--devfield.interview.auth.audience=",
                "--devfield.interview.auth.jwks-uri=", "--devfield.interview.auth.session-check-uri=",
                "--devfield.interview.auth.service-key=", "--devfield.interview.project.base-url=",
                "--devfield.interview.project.service-key=",
                "--devfield.interview.deletion.project-key-sha256=" + hash,
                "--devfield.interview.deletion.auth-key-sha256=")) {
            String interviewOrigin = "http://127.0.0.1:" + interview.getEnvironment().getRequiredProperty("local.server.port");
            JdbcTemplate interviewDb = interview.getBean(JdbcTemplate.class);
            UUID projectId;
            UUID sessionId;
            UUID jobId;
            try (var firstProject = startProject(interviewOrigin, UUID.randomUUID().toString())) {
                projectId = firstProject.getBean(Projects.class).register(owner,
                        new GitHubRepositoryClient.Repository("42", "fixture/public", "fixture"), "fixture").id();
                sessionId = interview.getBean(InterviewSessions.class)
                        .create(owner, projectId, InterviewSessions.Mode.FREE, snapshot()).id();
                jobId = firstProject.getBean(ProjectDeletions.class).request(owner, projectId).deletionJobId();

                JdbcTemplate projectDb = firstProject.getBean(JdbcTemplate.class);
                await(() -> "x02_auth_rejected".equals(projectDb.queryForObject("""
                        SELECT deletion_failure_code FROM project.project_write_fence
                        WHERE scope='PROJECT' AND owner_user_id=? AND project_id=?
                        """, String.class, owner, projectId)), "initial scheduler did not record the X-02 rejection");
                assertEquals(1, interviewSessionCount(interviewDb, sessionId));
                assertEquals("DELETING", projectDb.queryForObject(
                        "SELECT state FROM project.registered_project WHERE id=? AND owner_user_id=?",
                        String.class, projectId, owner));
            }

            try (var restartedProject = startProject(interviewOrigin, key)) {
                JdbcTemplate projectDb = restartedProject.getBean(JdbcTemplate.class);
                await(() -> "COMPLETED".equals(projectDb.queryForObject("""
                        SELECT state FROM project.project_write_fence
                        WHERE scope='PROJECT' AND owner_user_id=? AND project_id=?
                        """, String.class, owner, projectId)), "restarted scheduler did not complete deletion");
                assertEquals(0, projectDb.queryForObject(
                        "SELECT count(*) FROM project.registered_project WHERE id=?", Integer.class, projectId));
                assertEquals(0, interviewSessionCount(interviewDb, sessionId));
                assertEquals(jobId, interviewDb.queryForObject("""
                        SELECT deletion_job_id FROM interview.interview_write_fence
                        WHERE scope='PROJECT' AND owner_user_id=? AND project_id=? AND state='COMPLETED'
                        """, UUID.class, owner, projectId));
                assertEquals(jobId, projectDb.queryForObject("""
                        SELECT deletion_job_id FROM project.project_write_fence
                        WHERE scope='PROJECT' AND owner_user_id=? AND project_id=? AND state='COMPLETED'
                        """, UUID.class, owner, projectId));
            }
        }
    }

    private static ConfigurableApplicationContext startProject(String interviewOrigin, String key) {
        return new SpringApplicationBuilder(ProjectApplication.class).run(
                "--spring.config.location=classpath:/application.yml",
                "--server.address=127.0.0.1", "--server.port=0",
                dbSetting("PROJECT", "URL", "url"),
                dbSetting("PROJECT", "USER", "username"),
                dbSetting("PROJECT", "PASSWORD", "password"),
                "--devfield.project.analysis.enabled=false", "--devfield.project.analysis.source-enabled=false",
                "--devfield.project.github.app-id=0",
                "--devfield.project.auth.issuer=test-issuer", "--devfield.project.auth.audience=test-audience",
                "--devfield.project.auth.jwks-uri=http://127.0.0.1:1/jwks",
                "--devfield.project.auth.session-check-uri=http://127.0.0.1:1/internal/auth/session-check",
                "--devfield.project.auth.service-key=restart-fixture-auth-key",
                "--devfield.project.deletion.interview-url=" + interviewOrigin,
                "--devfield.project.deletion.service-key=" + key,
                "--devfield.project.deletion.timeout-seconds=2",
                "--devfield.project.deletion.retry-seconds=1",
                "--devfield.project.deletion.poll-interval=PT0.2S");
    }

    private static String dbSetting(String service, String envSuffix, String property) {
        return "--spring.datasource." + property + "=" + System.getenv(service + "_DB_" + envSuffix);
    }

    private static int interviewSessionCount(JdbcTemplate db, UUID sessionId) {
        return db.queryForObject("SELECT count(*) FROM interview.interview_session WHERE id=?", Integer.class, sessionId);
    }

    private static ProjectSnapshot snapshot() {
        String member = UUID.randomUUID().toString(), repository = UUID.randomUUID().toString();
        String evidence = UUID.randomUUID().toString(), sha = "a".repeat(40);
        return new ProjectSnapshot(UUID.randomUUID().toString(), 1,
                List.of(new ProjectSnapshot.Member(member, repository, "42", sha)),
                List.of(new ProjectSnapshot.Topic("JAVA", evidence)), List.of(),
                List.of(new ProjectSnapshot.Evidence(evidence, member, repository, "42", sha,
                        "src/Main.java", 1, 1, "CODE_FACT", "Declares a Java class.")));
    }

    private static void await(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, message);
            Thread.sleep(25);
        }
    }
}
