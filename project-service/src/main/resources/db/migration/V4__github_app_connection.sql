CREATE TABLE project.github_connection (
    id uuid PRIMARY KEY,
    devfield_user_id uuid NOT NULL UNIQUE,
    github_user_id bigint UNIQUE CHECK (github_user_id > 0),
    user_state text NOT NULL DEFAULT 'NOT_AUTHORIZED' CHECK (user_state IN ('NOT_AUTHORIZED','AUTHORIZED','REAUTHORIZE')),
    installation_state text NOT NULL DEFAULT 'NOT_INSTALLED' CHECK (installation_state IN ('NOT_INSTALLED','PENDING_APPROVAL','INSTALLED')),
    installation_id bigint CHECK (installation_id > 0),
    access_token_ciphertext text,
    refresh_token_ciphertext text,
    token_expires_at timestamptz,
    refresh_expires_at timestamptz,
    token_key_version integer CHECK (token_key_version > 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT github_connection_token_complete CHECK (
        (access_token_ciphertext IS NULL AND refresh_token_ciphertext IS NULL AND token_expires_at IS NULL
         AND refresh_expires_at IS NULL AND token_key_version IS NULL)
        OR (access_token_ciphertext IS NOT NULL AND refresh_token_ciphertext IS NOT NULL AND
            token_expires_at IS NOT NULL AND refresh_expires_at IS NOT NULL AND token_key_version IS NOT NULL)),
    CONSTRAINT github_connection_user_authorized CHECK (user_state <> 'AUTHORIZED' OR
        (github_user_id IS NOT NULL AND access_token_ciphertext IS NOT NULL)),
    CONSTRAINT github_connection_installation_confirmed CHECK (installation_state <> 'INSTALLED' OR
        (installation_id IS NOT NULL AND user_state = 'AUTHORIZED'))
);

CREATE TABLE project.github_connection_attempt (
    id uuid PRIMARY KEY,
    devfield_user_id uuid NOT NULL,
    devfield_session_id uuid NOT NULL,
    state_hash bytea NOT NULL UNIQUE CHECK (length(state_hash) = 32),
    kind text NOT NULL CHECK (kind IN ('AUTHORIZE_USER','INSTALL_APP')),
    pkce_verifier_ciphertext text,
    key_version integer CHECK (key_version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    CONSTRAINT github_attempt_pkce CHECK ((kind = 'AUTHORIZE_USER' AND pkce_verifier_ciphertext IS NOT NULL AND key_version IS NOT NULL)
        OR (kind = 'INSTALL_APP' AND pkce_verifier_ciphertext IS NULL AND key_version IS NULL)),
    CONSTRAINT github_attempt_expiry CHECK (expires_at > created_at),
    CONSTRAINT github_attempt_consumption CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);
CREATE INDEX github_attempt_pending_user ON project.github_connection_attempt (devfield_user_id, devfield_session_id, kind)
    WHERE consumed_at IS NULL;
CREATE INDEX github_attempt_expiry_cleanup ON project.github_connection_attempt (expires_at);

ALTER TABLE project.project_repository ADD COLUMN github_connection_id uuid;
ALTER TABLE project.github_connection ADD CONSTRAINT github_connection_id_owner_unique UNIQUE (id, devfield_user_id);
ALTER TABLE project.project_repository ADD CONSTRAINT project_repository_connection_owner_fk
    FOREIGN KEY (github_connection_id, owner_user_id)
    REFERENCES project.github_connection (id, devfield_user_id);
ALTER TABLE project.project_repository ADD CONSTRAINT project_repository_private_connection CHECK
    ((visibility = 'PUBLIC' AND github_connection_id IS NULL) OR
     (visibility = 'PRIVATE' AND github_connection_id IS NOT NULL));
