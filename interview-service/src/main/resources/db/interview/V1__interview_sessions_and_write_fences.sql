CREATE TABLE interview.interview_session (
    id uuid PRIMARY KEY,
    owner_user_id uuid NOT NULL,
    project_id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    membership_version bigint NOT NULL CHECK (membership_version > 0),
    snapshot_context jsonb NOT NULL CHECK (jsonb_typeof(snapshot_context) = 'object'),
    mode text NOT NULL CHECK (mode IN ('FREE', 'MOCK')),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX interview_session_owner_project ON interview.interview_session(owner_user_id, project_id);

CREATE TABLE interview.interview_write_fence (
    id uuid PRIMARY KEY,
    scope text NOT NULL CHECK (scope IN ('USER', 'PROJECT')),
    owner_user_id uuid NOT NULL,
    project_id uuid,
    state text NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE', 'DELETING', 'COMPLETED')),
    deletion_job_id uuid,
    completed_at timestamptz,
    CHECK ((scope = 'USER') = (project_id IS NULL)),
    CHECK ((state = 'ACTIVE') = (deletion_job_id IS NULL)),
    CHECK ((state = 'COMPLETED') = (completed_at IS NOT NULL))
);

CREATE UNIQUE INDEX interview_fence_user ON interview.interview_write_fence(owner_user_id) WHERE scope = 'USER';
CREATE UNIQUE INDEX interview_fence_project ON interview.interview_write_fence(project_id) WHERE scope = 'PROJECT';
CREATE UNIQUE INDEX interview_fence_deletion_job ON interview.interview_write_fence(scope, deletion_job_id)
    WHERE deletion_job_id IS NOT NULL;
