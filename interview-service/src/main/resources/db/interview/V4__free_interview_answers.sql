CREATE TABLE interview.question_turn (
    session_id uuid NOT NULL REFERENCES interview.interview_session(id) ON DELETE CASCADE,
    position integer NOT NULL CHECK (position BETWEEN 0 AND 3),
    id uuid NOT NULL UNIQUE,
    topic_key text NOT NULL,
    evidence_id uuid NOT NULL,
    question_text text NOT NULL CHECK (char_length(question_text) BETWEEN 1 AND 500),
    PRIMARY KEY (session_id, position),
    UNIQUE (session_id, id)
);

INSERT INTO interview.question_turn (session_id, position, id, topic_key, evidence_id, question_text)
SELECT session_id, 0, question_id, resolved_topic_key, evidence_id, question_text
FROM interview.first_question_job WHERE state='READY';

CREATE TABLE interview.user_answer (
    session_id uuid NOT NULL,
    question_id uuid NOT NULL,
    submission_id text NOT NULL CHECK (submission_id ~ '^[A-Za-z0-9_-]{1,128}$'),
    answer_text text NOT NULL CHECK (char_length(answer_text) BETWEEN 1 AND 4000),
    state text NOT NULL CHECK (state IN ('QUEUED','RUNNING','PUBLISHED','STOPPED','FAILED')),
    attempt integer NOT NULL DEFAULT 0 CHECK (attempt BETWEEN 0 AND 3),
    due_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_until timestamptz,
    PRIMARY KEY (session_id, question_id),
    UNIQUE (session_id, submission_id),
    FOREIGN KEY (session_id, question_id) REFERENCES interview.question_turn(session_id, id) ON DELETE CASCADE,
    CHECK ((state='RUNNING') = (lease_until IS NOT NULL))
);

CREATE INDEX user_answer_generation_due ON interview.user_answer(due_at, session_id)
    WHERE state IN ('QUEUED','RUNNING');
