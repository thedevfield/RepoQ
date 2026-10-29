CREATE TABLE project.project_write_fence (
    id uuid PRIMARY KEY,
    scope text NOT NULL CHECK (scope IN ('USER', 'PROJECT')),
    owner_user_id uuid NOT NULL,
    project_id uuid,
    state text NOT NULL CHECK (state IN ('ACTIVE', 'DELETING', 'COMPLETED')),
    deletion_job_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT fence_scope_project CHECK ((scope = 'USER' AND project_id IS NULL) OR
                                         (scope = 'PROJECT' AND project_id IS NOT NULL)),
    CONSTRAINT fence_deletion_job CHECK (state = 'ACTIVE' OR deletion_job_id IS NOT NULL)
);
CREATE UNIQUE INDEX fence_user_unique ON project.project_write_fence(owner_user_id) WHERE scope = 'USER';
CREATE UNIQUE INDEX fence_project_unique ON project.project_write_fence(project_id) WHERE scope = 'PROJECT';

CREATE TABLE project.registered_project (
    id uuid PRIMARY KEY,
    owner_user_id uuid NOT NULL,
    github_repository_id text NOT NULL,
    repository_full_name text NOT NULL,
    display_name text NOT NULL CHECK (length(trim(display_name)) > 0),
    state text NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE', 'DELETING')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT registered_project_owner_repository_unique UNIQUE (owner_user_id, github_repository_id)
);
CREATE INDEX registered_project_owner_created ON project.registered_project(owner_user_id, created_at DESC);

CREATE TABLE project.analysis_job (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES project.registered_project(id),
    trigger_kind text NOT NULL CHECK (trigger_kind IN ('INITIAL', 'USER_REANALYZE', 'RETRY')),
    state text NOT NULL CHECK (state IN ('QUEUED', 'RUNNING', 'READY', 'INSUFFICIENT_EVIDENCE', 'FAILED', 'CANCELLED')),
    commit_sha text,
    analysis_version text NOT NULL,
    failure_code text,
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz
);
CREATE UNIQUE INDEX analysis_job_one_active ON project.analysis_job(project_id)
    WHERE state IN ('QUEUED', 'RUNNING');
CREATE INDEX analysis_job_project_created ON project.analysis_job(project_id, created_at DESC);
