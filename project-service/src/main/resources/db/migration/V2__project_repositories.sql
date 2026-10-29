ALTER TABLE project.registered_project
    ADD COLUMN membership_version bigint NOT NULL DEFAULT 1 CHECK (membership_version > 0),
    ADD COLUMN create_request_key text,
    ADD COLUMN create_request_hash text;

CREATE UNIQUE INDEX registered_project_create_key_unique
    ON project.registered_project (owner_user_id, create_request_key)
    WHERE create_request_key IS NOT NULL;

ALTER TABLE project.registered_project
    ADD CONSTRAINT registered_project_id_owner_unique UNIQUE (id, owner_user_id);

CREATE TABLE project.project_repository (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    owner_user_id uuid NOT NULL,
    github_repository_id text NOT NULL,
    repository_full_name text NOT NULL,
    visibility text NOT NULL CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    access_state text NOT NULL DEFAULT 'ACTIVE' CHECK (access_state IN ('ACTIVE', 'BLOCKED')),
    added_at timestamptz NOT NULL DEFAULT now(),
    removed_at timestamptz,
    CONSTRAINT project_repository_project_owner_fk FOREIGN KEY (project_id, owner_user_id)
        REFERENCES project.registered_project (id, owner_user_id),
    CONSTRAINT project_repository_id_not_blank CHECK (length(trim(github_repository_id)) > 0),
    CONSTRAINT project_repository_name_not_blank CHECK (length(trim(repository_full_name)) > 0)
);

CREATE UNIQUE INDEX project_repository_owner_active_unique
    ON project.project_repository (owner_user_id, github_repository_id)
    WHERE removed_at IS NULL;
CREATE UNIQUE INDEX project_repository_project_active_unique
    ON project.project_repository (project_id, github_repository_id)
    WHERE removed_at IS NULL;
CREATE INDEX project_repository_project_active
    ON project.project_repository (project_id, added_at, id)
    WHERE removed_at IS NULL;

INSERT INTO project.project_repository
    (id, project_id, owner_user_id, github_repository_id, repository_full_name, visibility)
SELECT gen_random_uuid(), id, owner_user_id, github_repository_id, repository_full_name, 'PUBLIC'
FROM project.registered_project;

ALTER TABLE project.analysis_job
    ADD COLUMN membership_version bigint NOT NULL DEFAULT 1 CHECK (membership_version > 0),
    ADD COLUMN repository_progress jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(repository_progress) = 'array'),
    ADD COLUMN request_scope text,
    ADD COLUMN request_key text,
    ADD COLUMN request_hash text,
    ADD COLUMN response_json jsonb;

ALTER TABLE project.analysis_job
    DROP CONSTRAINT analysis_job_trigger_kind_check;
ALTER TABLE project.analysis_job
    ADD CONSTRAINT analysis_job_trigger_kind_check
        CHECK (trigger_kind IN ('INITIAL', 'USER_REANALYZE', 'RETRY', 'MEMBERSHIP_CHANGED'));

CREATE UNIQUE INDEX analysis_job_request_key_unique
    ON project.analysis_job (project_id, request_scope, request_key)
    WHERE request_key IS NOT NULL;

UPDATE project.analysis_job j
SET repository_progress = jsonb_build_array(jsonb_build_object(
    'repositoryId', r.id::text,
    'state', j.state,
    'commitSha', j.commit_sha
))
FROM project.project_repository r
WHERE r.project_id = j.project_id AND r.removed_at IS NULL;

DO $$
BEGIN
    IF (SELECT count(*) FROM project.registered_project) <>
       (SELECT count(*) FROM project.project_repository WHERE removed_at IS NULL) THEN
        RAISE EXCEPTION 'project_repository backfill count mismatch';
    END IF;
    IF EXISTS (
        SELECT 1 FROM project.registered_project p
        LEFT JOIN project.project_repository r ON r.project_id = p.id AND r.removed_at IS NULL
        WHERE r.id IS NULL OR r.owner_user_id <> p.owner_user_id
           OR r.github_repository_id <> p.github_repository_id
    ) THEN
        RAISE EXCEPTION 'project_repository backfill identity mismatch';
    END IF;
    IF EXISTS (
        SELECT 1 FROM project.analysis_job j
        WHERE jsonb_array_length(j.repository_progress) <> 1
    ) THEN
        RAISE EXCEPTION 'analysis_job backfill mismatch';
    END IF;
END $$;
