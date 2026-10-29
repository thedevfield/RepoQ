-- V2 has populated and verified the active project_repository uniqueness constraint.
-- The legacy project-level constraint would block re-registering a removed first member.
ALTER TABLE project.registered_project
    DROP CONSTRAINT registered_project_owner_repository_unique;
