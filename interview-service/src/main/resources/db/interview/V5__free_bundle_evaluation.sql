CREATE TABLE interview.bundle_evaluation (
    session_id uuid PRIMARY KEY,
    closing_question_id uuid NOT NULL,
    state text NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','RUNNING','READY','FAILED')),
    attempt integer NOT NULL DEFAULT 0 CHECK (attempt BETWEEN 0 AND 3),
    due_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_until timestamptz,
    result jsonb,
    FOREIGN KEY (session_id, closing_question_id)
        REFERENCES interview.user_answer(session_id, question_id) ON DELETE CASCADE,
    CHECK ((state='RUNNING') = (lease_until IS NOT NULL)),
    CHECK ((state='READY') = (result IS NOT NULL)),
    CHECK (result IS NULL OR jsonb_typeof(result)='object')
);

CREATE INDEX bundle_evaluation_due ON interview.bundle_evaluation(due_at, session_id)
    WHERE state IN ('QUEUED','RUNNING');
