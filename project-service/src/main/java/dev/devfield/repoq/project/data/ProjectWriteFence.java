package dev.devfield.repoq.project.data;

import dev.devfield.repoq.project.api.ApiProblem;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** Call only inside a transaction, always USER before PROJECT. */
public final class ProjectWriteFence {
    private ProjectWriteFence() {}

    public record UserFence(String state, UUID deletionJobId) {
        public boolean active() { return "ACTIVE".equals(state); }
    }

    public static void lockUser(JdbcTemplate db, UUID owner) {
        if (!lockUserFence(db, owner).active())
            throw new ApiProblem(HttpStatus.FORBIDDEN, "account_unavailable");
    }

    public static UserFence lockUserFence(JdbcTemplate db, UUID owner) {
        db.update("INSERT INTO project.project_write_fence(id,scope,owner_user_id,state) " +
                        "VALUES (?,'USER',?,'ACTIVE') ON CONFLICT (owner_user_id) WHERE scope='USER' DO NOTHING",
                UUID.randomUUID(), owner);
        return db.queryForObject("SELECT state,deletion_job_id FROM project.project_write_fence " +
                "WHERE scope='USER' AND owner_user_id=? FOR UPDATE",
                (rs, n) -> new UserFence(rs.getString(1), rs.getObject(2, UUID.class)), owner);
    }

    public static long lockProject(JdbcTemplate db, UUID owner, UUID id) {
        var fence = db.query("SELECT state FROM project.project_write_fence " +
                        "WHERE scope='PROJECT' AND owner_user_id=? AND project_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), owner, id);
        if (fence.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "project_not_found");
        if (!"ACTIVE".equals(fence.getFirst())) throw new ApiProblem(HttpStatus.CONFLICT, "project_unavailable");
        var projects = db.query("SELECT membership_version,state FROM project.registered_project " +
                        "WHERE id=? AND owner_user_id=? FOR UPDATE",
                (rs, row) -> new Object[]{rs.getLong(1), rs.getString(2)}, id, owner);
        if (projects.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "project_not_found");
        if (!"ACTIVE".equals(projects.getFirst()[1])) throw new ApiProblem(HttpStatus.CONFLICT, "project_unavailable");
        return (Long) projects.getFirst()[0];
    }

}
