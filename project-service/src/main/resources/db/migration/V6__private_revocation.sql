ALTER TABLE project.github_connection
    ADD COLUMN generation bigint NOT NULL DEFAULT 0 CHECK (generation >= 0);

ALTER TABLE project.github_connection
    DROP CONSTRAINT github_connection_user_state_check;
ALTER TABLE project.github_connection
    ADD CONSTRAINT github_connection_user_state_check
        CHECK (user_state IN ('NOT_AUTHORIZED', 'AUTHORIZED', 'REAUTHORIZE', 'DISCONNECTED'));

-- Existing attempts predate connection binding and must never complete after this migration.
UPDATE project.github_connection_attempt SET consumed_at=now() WHERE consumed_at IS NULL;
ALTER TABLE project.github_connection_attempt
    ADD COLUMN connection_id uuid REFERENCES project.github_connection(id),
    ADD COLUMN generation bigint NOT NULL DEFAULT 0 CHECK (generation >= 0),
    ADD CONSTRAINT github_attempt_connection_required
        CHECK (consumed_at IS NOT NULL OR connection_id IS NOT NULL);

CREATE TABLE project.github_webhook_delivery (
    delivery_id uuid PRIMARY KEY,
    event_name text NOT NULL,
    action text NOT NULL,
    payload_hash bytea NOT NULL CHECK (length(payload_hash) = 32),
    processed_at timestamptz NOT NULL DEFAULT now()
);
