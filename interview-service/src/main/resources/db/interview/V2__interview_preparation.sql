ALTER TABLE interview.interview_session
    ADD COLUMN state text NOT NULL DEFAULT 'PREPARING' CHECK (state = 'PREPARING'),
    ADD COLUMN requested_topic_key text CHECK (
        requested_topic_key IS NULL OR (mode = 'FREE' AND requested_topic_key ~ '^[A-Z][A-Z0-9_]{0,63}$')
    );
