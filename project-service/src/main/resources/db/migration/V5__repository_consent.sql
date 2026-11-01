CREATE TABLE project.repository_consent (
    id uuid PRIMARY KEY,
    event_order bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    owner_user_id uuid NOT NULL,
    github_repository_id text NOT NULL CHECK (length(trim(github_repository_id)) > 0),
    purpose text NOT NULL CHECK (purpose IN ('PRIVATE_COLLECTION', 'EXTERNAL_AI')),
    decision text NOT NULL CHECK (decision IN ('GRANTED', 'DENIED', 'REVOKED')),
    policy_version text NOT NULL CHECK (length(trim(policy_version)) > 0),
    recorded_at timestamptz NOT NULL DEFAULT now(),
    revocation_id uuid,
    cleanup_state text,
    CONSTRAINT repository_consent_revocation CHECK
        ((revocation_id IS NULL AND cleanup_state IS NULL) OR
         (decision = 'REVOKED' AND revocation_id IS NOT NULL AND cleanup_state IS NOT NULL))
);

CREATE INDEX repository_consent_latest ON project.repository_consent
    (owner_user_id, github_repository_id, purpose, event_order DESC);
