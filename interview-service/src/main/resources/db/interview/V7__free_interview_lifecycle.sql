ALTER TABLE interview.interview_session
    ADD COLUMN lifecycle text NOT NULL DEFAULT 'OPEN' CHECK (lifecycle IN ('OPEN','PAUSED','ENDED'));

ALTER TABLE interview.first_question_job
    DROP CONSTRAINT first_question_job_state_check,
    ADD CONSTRAINT first_question_job_state_check CHECK (state IN ('QUEUED','RUNNING','READY','FAILED','CANCELLED'));
CREATE INDEX first_question_job_session ON interview.first_question_job(session_id);

ALTER TABLE interview.user_answer
    DROP CONSTRAINT user_answer_state_check,
    ADD CONSTRAINT user_answer_state_check CHECK (state IN ('QUEUED','RUNNING','PUBLISHED','STOPPED','FAILED','CANCELLED'));

ALTER TABLE interview.bundle_evaluation
    DROP CONSTRAINT bundle_evaluation_state_check,
    ADD CONSTRAINT bundle_evaluation_state_check CHECK (state IN ('QUEUED','RUNNING','READY','FAILED','CANCELLED'));
