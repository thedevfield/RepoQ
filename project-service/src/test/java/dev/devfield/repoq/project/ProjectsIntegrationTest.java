package dev.devfield.repoq.project;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.devfield.repoq.project.api.ApiProblem;
import dev.devfield.repoq.project.data.Projects;
import dev.devfield.repoq.project.github.GitHubRepositoryClient;
import dev.devfield.repoq.project.security.AuthSessionVerifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "devfield.project.auth.issuer=test-issuer",
        "devfield.project.auth.audience=test-audience",
        "devfield.project.auth.jwks-uri=http://127.0.0.1:1/api/auth/jwks",
        "devfield.project.auth.session-check-uri=http://127.0.0.1:1/internal/auth/session-check",
        "devfield.project.auth.service-key=test-service-key"
})
class ProjectsIntegrationTest {
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate db;
    @Autowired Projects projects;
    private final ObjectMapper json = new ObjectMapper();
    @MockitoBean JwtDecoder decoder;
    @MockitoBean AuthSessionVerifier sessions;
    @MockitoBean GitHubRepositoryClient github;
    private final HttpClient http = HttpClient.newHttpClient();
    private UUID userA;
    private UUID userB;

    @BeforeEach
    void setUp() {
        userA = UUID.randomUUID();
        userB = UUID.randomUUID();
        when(decoder.decode(anyString())).thenAnswer(invocation -> {
            String token = invocation.getArgument(0);
            if (token.equals("forged")) throw new BadJwtException("bad token");
            UUID user = token.equals("user-b") ? userB : userA;
            return new Jwt(token, Instant.now().minusSeconds(10), Instant.now().plusSeconds(300),
                    Map.of("alg", "RS256"), Map.of("sub", user.toString(), "aud", List.of("test-audience")));
        });
        when(github.fetch("owner", "repo")).thenReturn(new GitHubRepositoryClient.Repository("1234567", "owner/repo", "repo"));
        when(github.fetch("owner", "repo2")).thenReturn(new GitHubRepositoryClient.Repository("2345678", "owner/repo2", "repo2"));
        when(github.fetch("owner", "repo3")).thenReturn(new GitHubRepositoryClient.Repository("3456789", "owner/repo3", "repo3"));
    }

    @AfterEach
    void cleanOwnRows() {
        db.update("DELETE FROM project.analysis_job WHERE project_id IN " +
                "(SELECT id FROM project.registered_project WHERE owner_user_id IN (?,?))", userA, userB);
        db.update("DELETE FROM project.project_repository WHERE owner_user_id IN (?,?)", userA, userB);
        db.update("DELETE FROM project.project_write_fence WHERE owner_user_id IN (?,?)", userA, userB);
        db.update("DELETE FROM project.registered_project WHERE owner_user_id IN (?,?)", userA, userB);
    }

    @Test
    void registrationAndOwnerScopedReads() throws Exception {
        var created = call("POST", "/api/projects", "user-a", "{\"repositoryUrl\":\"https://github.com/owner/repo\"}");
        assertEquals(201, created.statusCode());
        JsonNode project = json.readTree(created.body());
        assertEquals("QUEUED", project.get("analysisStatus").asText());
        UUID id = UUID.fromString(project.get("id").asText());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.analysis_job WHERE project_id=? AND state='QUEUED'", Integer.class, id));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.project_write_fence WHERE project_id=?", Integer.class, id));

        var detail = call("GET", "/api/projects/" + id, "user-a", null);
        assertEquals(200, detail.statusCode());
        assertFalse(json.readTree(detail.body()).get("canInterview").asBoolean());
        assertTrue(json.readTree(detail.body()).get("latestReadySnapshot").isNull());
        assertEquals(404, call("GET", "/api/projects/" + id, "user-b", null).statusCode());
        assertEquals(0, json.readTree(call("GET", "/api/projects", "user-b", null).body()).size());

        var duplicate = call("POST", "/api/projects", "user-a", "{\"repositoryUrl\":\"https://github.com/owner/repo/\"}");
        assertEquals(409, duplicate.statusCode());
        assertEquals("repository_already_registered", json.readTree(duplicate.body()).get("code").asText());
        assertEquals(201, call("POST", "/api/projects", "user-b", "{\"repositoryUrl\":\"https://github.com/owner/repo\"}").statusCode());
    }

    @Test
    void securityInputAndAuthFailure() throws Exception {
        assertEquals(200, call("GET", "/actuator/health", null, null).statusCode());
        assertEquals(401, call("GET", "/api/projects", null, null).statusCode());
        assertEquals("invalid_token", json.readTree(call("GET", "/api/projects", null, null).body()).get("code").asText());
        assertEquals(401, call("GET", "/api/projects", "forged", null).statusCode());
        assertEquals(400, call("GET", "/api/projects/not-a-uuid", "user-a", null).statusCode());
        assertEquals(400, call("POST", "/api/projects", "user-a", "{\"repositoryUrl\":\"http://127.0.0.1/a/b\"}").statusCode());
        assertEquals(400, call("POST", "/api/projects", "user-a", "{\"repositoryUrl\":\"https://github.com/owner/repo\",\"name\":\"   \"}").statusCode());
        doThrow(new ApiProblem(HttpStatus.FORBIDDEN, "account_unavailable")).when(sessions).verify("Bearer user-a");
        assertEquals(403, call("GET", "/api/projects", "user-a", null).statusCode());
        assertEquals(403, call("POST", "/api/projects/" + UUID.randomUUID() + "/repositories",
                "user-a", "{\"repositories\":[]}").statusCode());
        reset(sessions);
        doThrow(new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "auth_unavailable")).when(sessions).verify("Bearer user-a");
        assertEquals(503, call("GET", "/api/projects", "user-a", null).statusCode());
        assertEquals(503, call("DELETE", "/api/projects/" + UUID.randomUUID() +
                "/repositories/" + UUID.randomUUID(), "user-a", null).statusCode());
    }

    @Test
    void uniqueRaceFenceAndRollback() throws Exception {
        var repository = new GitHubRepositoryClient.Repository("race-" + UUID.randomUUID(), "owner/repo", "repo");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(i -> pool.submit(() -> {
                start.await();
                try { projects.register(userA, repository, "repo"); return "created"; }
                catch (ApiProblem e) { return e.code(); }
            })).toList();
            start.countDown();
            assertEquals(List.of("created", "repository_already_registered"),
                    futures.stream().map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } }).sorted().toList());
        }
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.registered_project WHERE owner_user_id=?", Integer.class, userA));
        UUID existingId = db.queryForObject("SELECT id FROM project.registered_project WHERE owner_user_id=?",
                UUID.class, userA);
        assertThrows(DataIntegrityViolationException.class, () -> db.update(
                "INSERT INTO project.analysis_job(id,project_id,trigger_kind,state,analysis_version) VALUES (?,?,'INITIAL','QUEUED','v1')",
                UUID.randomUUID(), existingId));
        assertThrows(DataIntegrityViolationException.class, () -> db.update(
                "INSERT INTO project.project_write_fence(id,scope,owner_user_id,state) VALUES (?,'USER',?,'ACTIVE')",
                UUID.randomUUID(), userA));
        assertThrows(DataIntegrityViolationException.class, () -> db.update(
                "INSERT INTO project.project_write_fence(id,scope,owner_user_id,project_id,state) VALUES (?,'PROJECT',?,?,'ACTIVE')",
                UUID.randomUUID(), userA, existingId));
        db.update("UPDATE project.project_write_fence SET state='DELETING',deletion_job_id=? " +
                "WHERE scope='USER' AND owner_user_id=?", UUID.randomUUID(), userA);
        assertEquals("account_unavailable", assertThrows(ApiProblem.class,
                () -> projects.register(userA, new GitHubRepositoryClient.Repository("other", "owner/other", "other"), "other")).code());

        assertThrows(DataAccessException.class, () -> projects.register(userB,
                new GitHubRepositoryClient.Repository("other", "owner/repo", "repo"), null));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.project_write_fence WHERE scope='USER' AND owner_user_id=?", Integer.class, userB));
    }

    @Test
    void multiRepositoryCreationAndScopedReads() throws Exception {
        var created = call("POST", "/api/projects", "user-a", """
                {"name":"Workspace","repositories":[
                  {"repositoryUrl":"https://github.com/owner/repo"},
                  {"repositoryUrl":"https://github.com/owner/repo2"}
                ]}
                """);
        assertEquals(201, created.statusCode());
        JsonNode response = json.readTree(created.body());
        assertEquals("QUEUED", response.get("analysisStatus").asText());
        assertEquals(2, response.get("repositories").size());
        UUID id = UUID.fromString(response.get("id").asText());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM project.project_repository " +
                "WHERE project_id=? AND removed_at IS NULL", Integer.class, id));
        assertEquals(2, db.queryForObject("SELECT jsonb_array_length(repository_progress) " +
                "FROM project.analysis_job WHERE project_id=?", Integer.class, id));
        assertEquals(1L, db.queryForObject("SELECT membership_version FROM project.registered_project WHERE id=?",
                Long.class, id));
        assertThrows(DataIntegrityViolationException.class, () -> db.update(
                "INSERT INTO project.project_repository" +
                        "(id,project_id,owner_user_id,github_repository_id,repository_full_name,visibility) " +
                        "VALUES (?,?,?,?,?,'PUBLIC')",
                UUID.randomUUID(), id, userB, "invalid-owner", "owner/invalid"));
        assertEquals(List.of("1", "2", "3"), db.query("SELECT version FROM project.flyway_schema_history " +
                "WHERE version IN ('1','2','3') AND success ORDER BY installed_rank",
                (rs, row) -> rs.getString(1)));

        JsonNode detail = json.readTree(call("GET", "/api/projects/" + id, "user-a", null).body());
        assertEquals(2, detail.get("repositories").size());
        assertEquals("QUEUED", detail.get("analysisStatus").asText());
        assertFalse(detail.get("canInterview").asBoolean());
        assertTrue(detail.get("latestReadySnapshot").isNull());
        assertEquals(2, json.readTree(call("GET", "/api/projects", "user-a", null).body())
                .get(0).get("repositoryCount").asInt());
        db.update("UPDATE project.analysis_job SET state='READY'," +
                "repository_progress=jsonb_set(repository_progress, '{0,state}', '\"READY\"'::jsonb) " +
                "WHERE project_id=?", id);
        JsonNode withoutSnapshot = json.readTree(call("GET", "/api/projects/" + id, "user-a", null).body());
        assertEquals("FAILED", withoutSnapshot.get("analysisStatus").asText());
        assertEquals("FAILED", withoutSnapshot.get("repositories").get(0).get("analysisStatus").asText());
        assertFalse(withoutSnapshot.get("canInterview").asBoolean());
        assertEquals("FAILED", json.readTree(call("GET", "/api/projects", "user-a", null).body())
                .get(0).get("analysisStatus").asText());
        assertEquals(404, call("GET", "/api/projects/" + id, "user-b", null).statusCode());
        assertEquals(201, call("POST", "/api/projects", "user-b", """
                {"repositoryUrl":"https://github.com/owner/repo"}
                """).statusCode());
        assertEquals(409, call("POST", "/api/projects", "user-a", """
                {"repositories":[{"repositoryUrl":"https://github.com/owner/repo"},
                                 {"repositoryUrl":"https://github.com/owner/repo"}]}
                """).statusCode());
        assertEquals(400, call("POST", "/api/projects", "user-a", "{" +
                "\"repositories\":[]}").statusCode());
        assertEquals(400, call("POST", "/api/projects", "user-a", """
                {"repositories":[{"githubRepositoryId":"999"}]}
                """).statusCode());
        assertEquals(400, call("POST", "/api/projects", "user-a", """
                {"repositories":[{"repositoryUrl":"https://github.com/owner/repo3",
                                  "visibility":"PRIVATE"}]}
                """).statusCode());
    }

    @Test
    void addRemoveRequeueAndReleaseRepositoryId() throws Exception {
        UUID first = UUID.fromString(json.readTree(call("POST", "/api/projects", "user-a", """
                {"repositoryUrl":"https://github.com/owner/repo"}
                """).body()).get("id").asText());
        db.update("UPDATE project.analysis_job SET state='RUNNING' WHERE project_id=?", first);
        assertEquals(409, call("POST", "/api/projects/" + first + "/repositories", "user-a", """
                {"repositories":[{"repositoryUrl":"https://github.com/owner/repo2"}]}
                """).statusCode());
        assertEquals(1L, db.queryForObject("SELECT membership_version FROM project.registered_project WHERE id=?",
                Long.class, first));
        db.update("UPDATE project.analysis_job SET state='QUEUED' WHERE project_id=?", first);
        var added = call("POST", "/api/projects/" + first + "/repositories", "user-a", """
                {"repositories":[{"repositoryUrl":"https://github.com/owner/repo2"},
                                 {"repositoryUrl":"https://github.com/owner/repo3"}]}
                """);
        assertEquals(201, added.statusCode());
        JsonNode addition = json.readTree(added.body());
        assertEquals(2L, addition.get("membershipVersion").asLong());
        assertEquals(3, addition.get("repositories").size());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.analysis_job " +
                "WHERE project_id=? AND state='CANCELLED'", Integer.class, first));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.analysis_job " +
                "WHERE project_id=? AND state='QUEUED'", Integer.class, first));
        assertEquals(3, db.queryForObject("SELECT jsonb_array_length(repository_progress) " +
                "FROM project.analysis_job WHERE project_id=? AND state='QUEUED'", Integer.class, first));
        assertEquals(2L, db.queryForObject("SELECT membership_version FROM project.analysis_job " +
                "WHERE project_id=? AND state='QUEUED'", Long.class, first));

        UUID removedMember = null;
        for (JsonNode member : addition.get("repositories"))
            if (member.get("githubRepositoryId").asText().equals("2345678"))
                removedMember = UUID.fromString(member.get("id").asText());
        assertNotNull(removedMember);
        db.update("UPDATE project.analysis_job SET state='RUNNING' WHERE project_id=? AND state='QUEUED'", first);
        assertEquals(409, call("DELETE", "/api/projects/" + first + "/repositories/" + removedMember,
                "user-a", null).statusCode());
        assertEquals(2L, db.queryForObject("SELECT membership_version FROM project.registered_project WHERE id=?",
                Long.class, first));
        assertEquals(3, db.queryForObject("SELECT count(*) FROM project.project_repository " +
                "WHERE project_id=? AND removed_at IS NULL", Integer.class, first));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.analysis_job " +
                "WHERE project_id=? AND state='RUNNING'", Integer.class, first));
        db.update("UPDATE project.analysis_job SET state='QUEUED' WHERE project_id=? AND state='RUNNING'", first);
        assertEquals(204, call("DELETE", "/api/projects/" + first + "/repositories/" + removedMember,
                "user-a", null).statusCode());
        assertEquals(204, call("DELETE", "/api/projects/" + first + "/repositories/" + removedMember,
                "user-a", null).statusCode());
        JsonNode detail = json.readTree(call("GET", "/api/projects/" + first, "user-a", null).body());
        assertEquals(3L, detail.get("membershipVersion").asLong());
        assertEquals(2, detail.get("repositories").size());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.analysis_job WHERE project_id=? " +
                "AND state='QUEUED'", Integer.class, first));
        assertEquals(2, db.queryForObject("SELECT jsonb_array_length(repository_progress) " +
                "FROM project.analysis_job WHERE project_id=? AND state='QUEUED'", Integer.class, first));
        assertEquals(3L, db.queryForObject("SELECT membership_version FROM project.analysis_job " +
                "WHERE project_id=? AND state='QUEUED'", Long.class, first));

        UUID second = UUID.fromString(json.readTree(call("POST", "/api/projects", "user-a", """
                {"repositoryUrl":"https://github.com/owner/repo2"}
                """).body()).get("id").asText());
        UUID secondMember = UUID.fromString(json.readTree(call("GET", "/api/projects/" + second,
                "user-a", null).body()).get("repositories").get(0).get("id").asText());
        assertEquals(409, call("DELETE", "/api/projects/" + second + "/repositories/" + secondMember,
                "user-a", null).statusCode());
        assertEquals(409, call("POST", "/api/projects/" + first + "/repositories", "user-a", """
                {"repositories":[{"repositoryUrl":"https://github.com/owner/repo2"}]}
                """).statusCode());
        assertEquals(404, call("DELETE", "/api/projects/" + first + "/repositories/" + secondMember,
                "user-a", null).statusCode());
        assertEquals(404, call("DELETE", "/api/projects/" + first + "/repositories/" + removedMember,
                "user-b", null).statusCode());
        assertEquals(404, call("POST", "/api/projects/" + first + "/repositories", "user-b", """
                {"repositories":[{"repositoryUrl":"https://github.com/owner/repo3"}]}
                """).statusCode());
        assertEquals(401, call("POST", "/api/projects/" + first + "/repositories", null,
                "{\"repositories\":[]}").statusCode());
        assertEquals(401, call("DELETE", "/api/projects/" + first + "/repositories/" + removedMember,
                "forged", null).statusCode());
    }

    @Test
    void addRaceRollbackAndIdempotency() throws Exception {
        var repoA = new GitHubRepositoryClient.Repository("a-" + UUID.randomUUID(), "owner/repo", "repo");
        var repoB = new GitHubRepositoryClient.Repository("b-" + UUID.randomUUID(), "owner/repo2", "repo2");
        var repoC = new GitHubRepositoryClient.Repository("c-" + UUID.randomUUID(), "owner/repo3", "repo3");
        UUID first = projects.register(userA, repoA, "A").id();
        UUID second = projects.register(userA, repoB, "B").id();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var futures = List.of(first, second).stream().map(id -> pool.submit(() -> {
                start.await();
                try { projects.add(userA, id, List.of(repoC), null, null); return "created"; }
                catch (ApiProblem e) { return e.code(); }
            })).toList();
            start.countDown();
            assertEquals(List.of("created", "repository_already_registered"), futures.stream()
                    .map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } })
                    .sorted().toList());
        }
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.project_repository " +
                "WHERE owner_user_id=? AND github_repository_id=? AND removed_at IS NULL",
                Integer.class, userA, repoC.id()));

        UUID loser = db.queryForObject("SELECT count(*) FROM project.project_repository WHERE project_id=? " +
                "AND github_repository_id=?", Integer.class, first, repoC.id()) == 0 ? first : second;

        var broken = new GitHubRepositoryClient.Repository("bad", "bad" + (char) 0 + "name", "bad");
        assertThrows(DataAccessException.class, () -> projects.add(userA, loser,
                List.of(new GitHubRepositoryClient.Repository("d", "owner/d", "d"), broken), null, null));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.project_repository " +
                "WHERE project_id=? AND github_repository_id='d'", Integer.class, loser));
        assertEquals(1L, db.queryForObject("SELECT membership_version FROM project.registered_project WHERE id=?",
                Long.class, loser));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.analysis_job WHERE project_id=? " +
                "AND state='QUEUED'", Integer.class, loser));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM project.analysis_job WHERE project_id=? " +
                "AND state='CANCELLED'", Integer.class, loser));

        var body = "{\"repositoryUrl\":\"https://github.com/owner/repo\"}";
        var firstResponse = call("POST", "/api/projects", "user-b", body, "create-key");
        when(github.fetch("owner", "repo")).thenThrow(new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_unavailable"));
        var replay = call("POST", "/api/projects", "user-b", body, "create-key");
        assertEquals(201, firstResponse.statusCode());
        assertEquals(firstResponse.body(), replay.body());
        assertEquals(409, call("POST", "/api/projects", "user-b", """
                {"repositoryUrl":"https://github.com/owner/repo2"}
                """, "create-key").statusCode());
        UUID project = UUID.fromString(json.readTree(firstResponse.body()).get("id").asText());
        var addBody = "{\"repositories\":[{\"repositoryUrl\":\"https://github.com/owner/repo2\"}]}";
        var addFirst = call("POST", "/api/projects/" + project + "/repositories", "user-b", addBody, "add-key");
        when(github.fetch("owner", "repo2")).thenThrow(new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "github_unavailable"));
        var addReplay = call("POST", "/api/projects/" + project + "/repositories", "user-b", addBody, "add-key");
        assertEquals(201, addFirst.statusCode());
        assertEquals(addFirst.body(), addReplay.body());
        assertEquals(2L, json.readTree(call("GET", "/api/projects/" + project, "user-b", null).body())
                .get("membershipVersion").asLong());
    }

    @Test
    void concurrentRemovalsNeverLeaveAnEmptyProject() throws Exception {
        var project = projects.register(userA, List.of(
                new GitHubRepositoryClient.Repository("remove-a", "owner/a", "a"),
                new GitHubRepositoryClient.Repository("remove-b", "owner/b", "b")),
                "two", null, null);
        UUID id = project.id();
        var members = project.repositories().stream().map(Projects.Member::id).toList();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var futures = members.stream().map(memberId -> pool.submit(() -> {
                start.await();
                try { projects.remove(userA, id, memberId); return "removed"; }
                catch (ApiProblem e) { return e.code(); }
            })).toList();
            start.countDown();
            assertEquals(List.of("last_repository_required", "removed"), futures.stream()
                    .map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } })
                    .sorted().toList());
        }
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.project_repository " +
                "WHERE project_id=? AND removed_at IS NULL", Integer.class, id));
        assertEquals(2L, db.queryForObject("SELECT membership_version FROM project.registered_project WHERE id=?",
                Long.class, id));
    }

    @Test
    void terminalJobsAllowMembershipChanges() {
        var first = new GitHubRepositoryClient.Repository("terminal-a", "owner/a", "a");
        var second = new GitHubRepositoryClient.Repository("terminal-b", "owner/b", "b");
        var third = new GitHubRepositoryClient.Repository("terminal-c", "owner/c", "c");
        var project = projects.register(userA, List.of(first, second), "terminal", null, null);
        UUID id = project.id();
        db.update("UPDATE project.analysis_job SET state='FAILED' WHERE project_id=?", id);

        var addition = projects.add(userA, id, List.of(third), null, null);
        assertEquals(2L, addition.membershipVersion());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.analysis_job " +
                "WHERE project_id=? AND state='QUEUED'", Integer.class, id));
        db.update("UPDATE project.analysis_job SET state='INSUFFICIENT_EVIDENCE' " +
                "WHERE project_id=? AND state='QUEUED'", id);

        projects.remove(userA, id, project.repositories().getFirst().id());
        assertEquals(3L, db.queryForObject("SELECT membership_version FROM project.registered_project WHERE id=?",
                Long.class, id));
        assertEquals(3L, db.queryForObject("SELECT membership_version FROM project.analysis_job " +
                "WHERE project_id=? AND state='QUEUED'", Long.class, id));
        assertEquals(2, db.queryForObject("SELECT jsonb_array_length(repository_progress) " +
                "FROM project.analysis_job WHERE project_id=? AND state='QUEUED'", Integer.class, id));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.analysis_job " +
                "WHERE project_id=? AND state='QUEUED'", Integer.class, id));
    }

    @Test
    void concurrentAddAndRemoveSerializeOnOneProject() throws Exception {
        var project = projects.register(userA, List.of(
                new GitHubRepositoryClient.Repository("race-a", "owner/a", "a"),
                new GitHubRepositoryClient.Repository("race-b", "owner/b", "b")),
                "race", null, null);
        UUID id = project.id();
        UUID removed = project.repositories().getFirst().id();
        var added = new GitHubRepositoryClient.Repository("race-c", "owner/c", "c");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var add = pool.submit(() -> { start.await(); projects.add(userA, id, List.of(added), null, null); return true; });
            var remove = pool.submit(() -> { start.await(); projects.remove(userA, id, removed); return true; });
            start.countDown();
            assertTrue(add.get());
            assertTrue(remove.get());
        }
        assertEquals(3L, db.queryForObject("SELECT membership_version FROM project.registered_project WHERE id=?",
                Long.class, id));
        assertEquals(2, db.queryForObject("SELECT count(*) FROM project.project_repository " +
                "WHERE project_id=? AND removed_at IS NULL", Integer.class, id));
        assertEquals(2, db.queryForObject("SELECT count(*) FROM project.analysis_job " +
                "WHERE project_id=? AND state='CANCELLED'", Integer.class, id));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM project.analysis_job " +
                "WHERE project_id=? AND state='QUEUED'", Integer.class, id));
        assertEquals(3L, db.queryForObject("SELECT membership_version FROM project.analysis_job " +
                "WHERE project_id=? AND state='QUEUED'", Long.class, id));
        assertEquals(2, db.queryForObject("SELECT jsonb_array_length(repository_progress) " +
                "FROM project.analysis_job WHERE project_id=? AND state='QUEUED'", Integer.class, id));
    }

    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        return call(method, path, token, body, null);
    }

    private HttpResponse<String> call(String method, String path, String token, String body, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
