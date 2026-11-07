ALTER TABLE project.project_write_fence
    ADD COLUMN deletion_next_attempt_at timestamptz,
    ADD COLUMN deletion_attempt_count bigint NOT NULL DEFAULT 0 CHECK (deletion_attempt_count >= 0),
    ADD COLUMN deletion_failure_code text,
    ADD COLUMN deleted_create_key_hash bytea;

CREATE INDEX project_deletion_due ON project.project_write_fence(deletion_next_attempt_at, project_id)
    WHERE scope='PROJECT' AND state='DELETING';

CREATE UNIQUE INDEX project_deleted_create_key ON project.project_write_fence(owner_user_id, deleted_create_key_hash)
    WHERE scope='PROJECT' AND deleted_create_key_hash IS NOT NULL;
