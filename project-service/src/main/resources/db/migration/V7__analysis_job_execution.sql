ALTER TABLE project.analysis_job
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    ADD COLUMN lease_expires_at timestamptz,
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();

CREATE INDEX analysis_job_claim ON project.analysis_job(next_attempt_at, created_at, id)
    WHERE state = 'QUEUED';
CREATE INDEX analysis_job_expired ON project.analysis_job(lease_expires_at, id)
    WHERE state = 'RUNNING';
