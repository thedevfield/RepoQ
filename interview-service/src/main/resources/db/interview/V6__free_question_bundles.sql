CREATE TABLE interview.question_bundle (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES interview.interview_session(id) ON DELETE CASCADE,
    sequence integer NOT NULL CHECK (sequence > 0),
    previous_bundle_id uuid,
    requested_topic_key text,
    UNIQUE (session_id, id),
    UNIQUE (session_id, sequence),
    UNIQUE (session_id, previous_bundle_id),
    FOREIGN KEY (session_id, previous_bundle_id) REFERENCES interview.question_bundle(session_id, id),
    CHECK ((sequence=1) = (previous_bundle_id IS NULL))
);

INSERT INTO interview.question_bundle(id, session_id, sequence, requested_topic_key)
SELECT id,id,1,requested_topic_key FROM interview.interview_session;

ALTER TABLE interview.first_question_job ADD COLUMN bundle_id uuid;
UPDATE interview.first_question_job SET bundle_id=session_id;
ALTER TABLE interview.first_question_job
    ALTER COLUMN bundle_id SET NOT NULL,
    DROP CONSTRAINT first_question_job_pkey,
    ADD PRIMARY KEY (bundle_id),
    ADD FOREIGN KEY (session_id,bundle_id) REFERENCES interview.question_bundle(session_id,id) ON DELETE CASCADE;

ALTER TABLE interview.question_turn ADD COLUMN bundle_id uuid;
UPDATE interview.question_turn SET bundle_id=session_id;
ALTER TABLE interview.question_turn
    ALTER COLUMN bundle_id SET NOT NULL,
    DROP CONSTRAINT question_turn_pkey,
    ADD PRIMARY KEY (bundle_id,position),
    ADD UNIQUE (session_id,bundle_id,id),
    ADD FOREIGN KEY (session_id,bundle_id) REFERENCES interview.question_bundle(session_id,id) ON DELETE CASCADE;

ALTER TABLE interview.bundle_evaluation ADD COLUMN bundle_id uuid;
UPDATE interview.bundle_evaluation SET bundle_id=session_id;
ALTER TABLE interview.bundle_evaluation
    ALTER COLUMN bundle_id SET NOT NULL,
    DROP CONSTRAINT bundle_evaluation_pkey,
    ADD PRIMARY KEY (bundle_id),
    ADD FOREIGN KEY (session_id,bundle_id,closing_question_id)
        REFERENCES interview.question_turn(session_id,bundle_id,id) ON DELETE CASCADE;
CREATE INDEX bundle_evaluation_session ON interview.bundle_evaluation(session_id);
