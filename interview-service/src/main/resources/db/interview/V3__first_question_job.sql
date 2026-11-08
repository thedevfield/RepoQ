ALTER TABLE interview.interview_session
    DROP CONSTRAINT interview_session_state_check,
    ADD CONSTRAINT interview_session_state_check CHECK (state IN ('PREPARING','ACTIVE','GENERATION_FAILED'));

CREATE TABLE interview.first_question_job (
    session_id uuid PRIMARY KEY REFERENCES interview.interview_session(id) ON DELETE CASCADE,
    resolved_topic_key text NOT NULL CHECK (resolved_topic_key ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    evidence_id uuid NOT NULL,
    state text NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','RUNNING','READY','FAILED')),
    attempt integer NOT NULL DEFAULT 0 CHECK (attempt BETWEEN 0 AND 3),
    due_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_until timestamptz,
    question_id uuid UNIQUE,
    question_text text,
    CHECK ((state='RUNNING') = (lease_until IS NOT NULL)),
    CHECK ((state='READY' AND question_id IS NOT NULL AND question_text IS NOT NULL)
        OR (state<>'READY' AND question_id IS NULL AND question_text IS NULL)),
    CHECK (question_text IS NULL OR char_length(question_text) BETWEEN 1 AND 500)
);

CREATE INDEX first_question_job_due ON interview.first_question_job(due_at,session_id)
    WHERE state IN ('QUEUED','RUNNING');
