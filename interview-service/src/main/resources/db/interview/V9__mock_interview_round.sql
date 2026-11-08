ALTER TABLE interview.interview_session
    DROP CONSTRAINT interview_session_state_check,
    ADD CONSTRAINT interview_session_state_check
        CHECK (state IN ('PREPARING','ACTIVE','GENERATION_FAILED','EVALUATING','COMPLETED'));

CREATE TABLE interview.mock_round (
    session_id uuid PRIMARY KEY REFERENCES interview.interview_session(id) ON DELETE CASCADE,
    current_sequence integer NOT NULL DEFAULT 1 CHECK (current_sequence BETWEEN 1 AND 5),
    summary jsonb CHECK (summary IS NULL OR jsonb_typeof(summary)='object')
);

CREATE TABLE interview.mock_job (
    session_id uuid NOT NULL REFERENCES interview.mock_round(session_id) ON DELETE CASCADE,
    kind text NOT NULL CHECK (kind IN ('PLAN','SUMMARY')),
    state text NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','RUNNING','READY','FAILED')),
    attempt integer NOT NULL DEFAULT 0 CHECK (attempt BETWEEN 0 AND 3),
    due_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_until timestamptz,
    PRIMARY KEY (session_id,kind),
    CHECK ((state='RUNNING') = (lease_until IS NOT NULL))
);
CREATE INDEX mock_job_due ON interview.mock_job(due_at,session_id) WHERE state IN ('QUEUED','RUNNING');
