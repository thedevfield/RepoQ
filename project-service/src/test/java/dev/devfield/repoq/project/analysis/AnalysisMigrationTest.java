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
        Flyway.configure().dataSource(source).schemas("project").defaultSchema("project")
                .locations("classpath:db/migration").target("8").load().migrate();
        UUID readyJob = db.queryForObject("SELECT id FROM project.analysis_job WHERE state='READY'", UUID.class);
        UUID readyProject = db.queryForObject("SELECT project_id FROM project.analysis_job WHERE id=?", UUID.class, readyJob);
        UUID readyRepository = db.queryForObject("SELECT id FROM project.project_repository WHERE project_id=?", UUID.class, readyProject);
        UUID snapshot = UUID.randomUUID(), member = UUID.randomUUID(), evidence = UUID.randomUUID();
        db.update("UPDATE project.analysis_job SET state='RUNNING' WHERE id=?", readyJob);
        db.update("INSERT INTO project.analysis_snapshot(id,project_id,analysis_job_id,membership_version,analysis_version,roles,relations,coverage) " +
                "VALUES (?,?,?,1,'v1','[]','[]','[]')", snapshot, readyProject, readyJob);
        db.update("INSERT INTO project.snapshot_member VALUES (?,?,?,?,? ,?,'PUBLIC')", member, snapshot, readyProject, readyRepository, "42", "a".repeat(40));
        db.update("INSERT INTO project.evidence_reference VALUES (?,?,?,?,?, 'src/App.java',1,2,'CODE_FACT','Safe summary')", evidence, snapshot, member, "42", "a".repeat(40));
        db.update("INSERT INTO project.available_topic VALUES (?,?, 'API_BACKEND',true,?)", UUID.randomUUID(), snapshot, evidence);
        db.update("UPDATE project.analysis_job SET state='READY' WHERE id=?", readyJob);
        db.update("UPDATE project.registered_project SET latest_ready_snapshot_id=? WHERE id=?", snapshot, readyProject);
        Map<String, List<Map<String, Object>>> snapshotRows = new HashMap<>();
        for (String table : List.of("analysis_snapshot", "snapshot_member", "evidence_reference", "available_topic"))
            snapshotRows.put(table, db.queryForList("SELECT to_jsonb(t)::text AS body FROM project." + table + " t ORDER BY id"));
        var v8Checksums = db.queryForList("SELECT version,checksum FROM project.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        var v8Jobs = db.queryForList("SELECT to_jsonb(j)::text AS body FROM project.analysis_job j ORDER BY id");
        var flyway = Flyway.configure().dataSource(source).schemas("project").defaultSchema("project")
                .locations("classpath:db/migration").target("9").load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        assertEquals(v8Checksums, db.queryForList("SELECT version,checksum FROM project.flyway_schema_history WHERE version IN ('1','2','3','4','5','6','7','8') ORDER BY installed_rank"));
        assertEquals(v8Jobs, db.queryForList("SELECT (to_jsonb(j)-'failure_count')::text AS body FROM project.analysis_job j ORDER BY id"));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.analysis_job WHERE failure_count<>0", Integer.class));
        flyway.validate();
        UUID interrupted=db.queryForObject("SELECT id FROM project.analysis_job WHERE state='RUNNING'",UUID.class);
        db.update("UPDATE project.analysis_job SET attempt_count=2 WHERE id=?",interrupted);
        db.update("INSERT INTO project.analysis_repository_checkpoint(analysis_job_id,project_id,project_repository_id,membership_version," +
                "github_repository_id,commit_sha,analysis_version,analyzer_policy_version,completed_file_count,completed_bytes,draft,expires_at) " +
                "SELECT j.id,j.project_id,r.id,j.membership_version,r.github_repository_id,?,'v1','java-syntax-public-v2',1,100," +
                "'{\"members\":[{}],\"evidence\":[],\"topics\":[],\"coverage\":[{}],\"roles\":[],\"relations\":[]}'::jsonb,clock_timestamp()+interval '1 hour' " +
                "FROM project.analysis_job j JOIN project.project_repository r ON r.project_id=j.project_id WHERE j.id=?","a".repeat(40),interrupted);
        var checkpoints=db.queryForList("SELECT * FROM project.analysis_repository_checkpoint");
        var v9Jobs = db.queryForList("SELECT to_jsonb(j)::text AS body FROM project.analysis_job j ORDER BY id");
        var v9Checksums = db.queryForList("SELECT version,checksum FROM project.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        flyway = Flyway.configure().dataSource(source).schemas("project").defaultSchema("project")
                .locations("classpath:db/migration").load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        assertEquals(40,db.queryForObject("SELECT download_count FROM project.analysis_job WHERE id=?",Integer.class,interrupted));
        assertEquals(2097152,db.queryForObject("SELECT download_bytes FROM project.analysis_job WHERE id=?",Integer.class,interrupted));
        assertEquals(checkpoints,db.queryForList("SELECT * FROM project.analysis_repository_checkpoint"));
        assertEquals(v9Jobs, db.queryForList("SELECT (to_jsonb(j)-'download_count'-'download_bytes')::text AS body FROM project.analysis_job j ORDER BY id"));
        assertEquals(v9Checksums, db.queryForList("SELECT version,checksum FROM project.flyway_schema_history WHERE version IN ('1','2','3','4','5','6','7','8','9') ORDER BY installed_rank"));
        flyway.validate();
        assertEquals(checksums, db.queryForList("SELECT version,checksum FROM project.flyway_schema_history WHERE version IN ('1','2','3','4','5','6') ORDER BY installed_rank"));
        assertEquals(projects, db.queryForList("SELECT (to_jsonb(p)-'latest_ready_snapshot_id')::text AS body FROM project.registered_project p ORDER BY id"));
        assertEquals(jobs, db.queryForList("SELECT (to_jsonb(j)-'attempt_count'-'lease_expires_at'-'next_attempt_at'-'failure_count'-'download_count'-'download_bytes')::text AS body FROM project.analysis_job j ORDER BY id"));
        assertEquals(members, db.queryForList("SELECT * FROM project.project_repository ORDER BY id"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.analysis_snapshot", Integer.class));
        for (var entry : snapshotRows.entrySet()) assertEquals(entry.getValue(),
                db.queryForList("SELECT to_jsonb(t)::text AS body FROM project." + entry.getKey() + " t ORDER BY id"));
        assertEquals(snapshot, db.queryForObject("SELECT latest_ready_snapshot_id FROM project.registered_project WHERE id=?", UUID.class, readyProject));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.analysis_job WHERE attempt_count<>0 OR lease_expires_at IS NOT NULL", Integer.class));
        assertEquals(0, flyway.migrate().migrationsExecuted);
        // Keep the upgrade fixture for inspection; the isolated container owns its lifetime.
    }
}
