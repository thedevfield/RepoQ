ALTER TABLE project.analysis_job
    ADD COLUMN download_count integer NOT NULL DEFAULT 0 CHECK (download_count BETWEEN 0 AND 40),
    ADD COLUMN download_bytes integer NOT NULL DEFAULT 0 CHECK (download_bytes BETWEEN 0 AND 2097152);

-- V9 cannot prove the usage of interrupted downloads. Never grant those executions a fresh budget.
UPDATE project.analysis_job SET download_count=40,download_bytes=2097152 WHERE attempt_count>0;

CREATE TABLE project.analysis_file_progress (
    analysis_job_id uuid NOT NULL,
    project_id uuid NOT NULL,
    project_repository_id uuid NOT NULL,
    membership_version bigint NOT NULL CHECK (membership_version>0),
    github_repository_id text NOT NULL,
    commit_sha text NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    blob_sha text NOT NULL CHECK (blob_sha ~ '^[0-9a-f]{40}$'),
    file_path text NOT NULL CHECK (length(file_path) BETWEEN 1 AND 1024 AND length(trim(file_path))>0
        AND file_path !~ '(^/|/$|//|(^|/)\.{1,2}(/|$)|[[:cntrl:]]|:)' AND strpos(file_path,chr(92))=0),
    expected_bytes integer NOT NULL CHECK (expected_bytes BETWEEN 0 AND 102400),
    policy_version text NOT NULL,
    attempt_count integer NOT NULL CHECK (attempt_count>0),
    download_count integer NOT NULL CHECK (download_count BETWEEN 1 AND 40),
    state text NOT NULL CHECK (state IN ('RESERVED','COLLECTED')),
    draft jsonb,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL CHECK (expires_at>created_at),
    CHECK ((state='RESERVED' AND draft IS NULL) OR
        (state='COLLECTED' AND draft IS NOT NULL AND jsonb_typeof(draft)='object'
        AND octet_length(draft::text)<=524288
        AND draft ?& ARRAY['members','evidence','topics','roles','relations','coverage'])),
    PRIMARY KEY (analysis_job_id,project_repository_id,file_path),
    FOREIGN KEY (analysis_job_id,project_id,membership_version)
        REFERENCES project.analysis_job(id,project_id,membership_version) ON DELETE CASCADE,
    FOREIGN KEY (project_repository_id,project_id,github_repository_id)
        REFERENCES project.project_repository(id,project_id,github_repository_id)
);
CREATE INDEX analysis_file_expiry ON project.analysis_file_progress(expires_at);

CREATE FUNCTION project.clear_terminal_analysis_files() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.state NOT IN ('QUEUED','RUNNING') THEN
        DELETE FROM project.analysis_file_progress WHERE analysis_job_id=NEW.id;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER analysis_files_terminal AFTER UPDATE OF state ON project.analysis_job
    FOR EACH ROW EXECUTE FUNCTION project.clear_terminal_analysis_files();
