ALTER TABLE project.analysis_job
    ADD COLUMN failure_count integer NOT NULL DEFAULT 0 CHECK (failure_count >= 0),
    ADD CONSTRAINT analysis_job_checkpoint_identity UNIQUE (id, project_id, membership_version);

CREATE TABLE project.analysis_repository_checkpoint (
    analysis_job_id uuid NOT NULL,
    project_id uuid NOT NULL,
    project_repository_id uuid NOT NULL,
    membership_version bigint NOT NULL CHECK (membership_version > 0),
    github_repository_id text NOT NULL,
    commit_sha text NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    analysis_version text NOT NULL CHECK (length(trim(analysis_version)) BETWEEN 1 AND 100),
    analyzer_policy_version text NOT NULL CHECK (length(trim(analyzer_policy_version)) BETWEEN 1 AND 100),
    completed_file_count integer NOT NULL CHECK (completed_file_count BETWEEN 0 AND 40),
    completed_bytes integer NOT NULL CHECK (completed_bytes BETWEEN 0 AND 2097152),
    draft jsonb NOT NULL CHECK (jsonb_typeof(draft) = 'object' AND octet_length(draft::text) <= 524288
        AND jsonb_typeof(draft->'members') = 'array' AND jsonb_array_length(draft->'members') = 1
        AND jsonb_typeof(draft->'evidence') = 'array' AND jsonb_array_length(draft->'evidence') <= 200
        AND jsonb_typeof(draft->'topics') = 'array' AND jsonb_array_length(draft->'topics') <= 64
        AND jsonb_typeof(draft->'coverage') = 'array' AND jsonb_array_length(draft->'coverage') = 1
        AND jsonb_typeof(draft->'roles') = 'array' AND jsonb_array_length(draft->'roles') = 0
        AND jsonb_typeof(draft->'relations') = 'array' AND jsonb_array_length(draft->'relations') = 0
        AND draft ?& ARRAY['members','evidence','topics','roles','relations','coverage']),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL CHECK (expires_at > created_at),
    PRIMARY KEY (analysis_job_id, project_repository_id),
    FOREIGN KEY (analysis_job_id, project_id, membership_version)
        REFERENCES project.analysis_job(id, project_id, membership_version) ON DELETE CASCADE,
    FOREIGN KEY (project_repository_id, project_id, github_repository_id)
        REFERENCES project.project_repository(id, project_id, github_repository_id)
);
CREATE INDEX analysis_checkpoint_expiry ON project.analysis_repository_checkpoint(expires_at);

-- All existing cancellation and terminal writers clean transient results in the same transaction.
CREATE FUNCTION project.clear_terminal_analysis_checkpoint() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.state NOT IN ('QUEUED','RUNNING') THEN
        DELETE FROM project.analysis_repository_checkpoint WHERE analysis_job_id = NEW.id;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER analysis_checkpoint_terminal AFTER UPDATE OF state ON project.analysis_job
    FOR EACH ROW EXECUTE FUNCTION project.clear_terminal_analysis_checkpoint();
