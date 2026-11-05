package dev.devfield.repoq.project.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AnalysisMigrationTest {
    @Test
    void tc17UpgradePreservesLegacyRowsAndChecksums() {
        String url = System.getenv("PROJECT_DB_URL");
        assertNotNull(url, "Migration verification requires an explicitly isolated PROJECT_DB_URL");
        assertTrue(url.contains("repoq_analysis_test"), "Refuse database creation outside the isolated analysis test instance");
        String user = System.getenv("PROJECT_DB_USER"), password = System.getenv("PROJECT_DB_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        String database = "repoq_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database);
        String upgradeUrl = url.replaceFirst("/repoq_analysis_test[^?]*", "/" + database);
        var source = new DriverManagerDataSource(upgradeUrl, user, password);
        Flyway.configure().dataSource(source).schemas("project").defaultSchema("project")
                .locations("classpath:db/migration").target("6").load().migrate();
        JdbcTemplate db = new JdbcTemplate(source);
        for (String state : List.of("QUEUED", "RUNNING", "READY", "FAILED", "CANCELLED", "INSUFFICIENT_EVIDENCE")) {
            UUID project = UUID.randomUUID(), owner = UUID.randomUUID(), member = UUID.randomUUID();
            db.update("INSERT INTO project.registered_project(id,owner_user_id,github_repository_id,repository_full_name,display_name) " +
                    "VALUES (?,?,'42','owner/repo','Legacy')", project, owner);
            db.update("INSERT INTO project.project_repository(id,project_id,owner_user_id,github_repository_id,repository_full_name,visibility) " +
                    "VALUES (?,?,?,'42','owner/repo','PUBLIC')", member, project, owner);
            db.update("INSERT INTO project.analysis_job(id,project_id,trigger_kind,state,analysis_version,repository_progress) " +
                    "VALUES (?,?,'INITIAL',?,'v1',jsonb_build_array(jsonb_build_object('repositoryId',?::text,'state',?::text)))",
                    UUID.randomUUID(), project, state, member, state);
        }
        var checksums = db.queryForList("SELECT version,checksum FROM project.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        var projects = db.queryForList("SELECT to_jsonb(p)::text AS body FROM project.registered_project p ORDER BY id");
        var jobs = db.queryForList("SELECT to_jsonb(j)::text AS body FROM project.analysis_job j ORDER BY id");
        var members = db.queryForList("SELECT * FROM project.project_repository ORDER BY id");
        var flyway = Flyway.configure().dataSource(source).schemas("project").defaultSchema("project")
                .locations("classpath:db/migration").load();
        assertEquals(2, flyway.migrate().migrationsExecuted);
        flyway.validate();
        assertEquals(checksums, db.queryForList("SELECT version,checksum FROM project.flyway_schema_history WHERE version IN ('1','2','3','4','5','6') ORDER BY installed_rank"));
        assertEquals(projects, db.queryForList("SELECT (to_jsonb(p)-'latest_ready_snapshot_id')::text AS body FROM project.registered_project p ORDER BY id"));
        assertEquals(jobs, db.queryForList("SELECT (to_jsonb(j)-'attempt_count'-'lease_expires_at'-'next_attempt_at')::text AS body FROM project.analysis_job j ORDER BY id"));
        assertEquals(members, db.queryForList("SELECT * FROM project.project_repository ORDER BY id"));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.analysis_snapshot", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.analysis_job WHERE attempt_count<>0 OR lease_expires_at IS NOT NULL", Integer.class));
        assertEquals(0, flyway.migrate().migrationsExecuted);
        // Keep the upgrade fixture for inspection; the isolated container owns its lifetime.
    }
}
