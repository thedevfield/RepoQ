ALTER TABLE project.analysis_job ADD CONSTRAINT analysis_job_id_project_unique UNIQUE (id, project_id);
ALTER TABLE project.project_repository ADD CONSTRAINT project_repository_identity_unique
    UNIQUE (id, project_id, github_repository_id);

CREATE TABLE project.analysis_snapshot (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES project.registered_project(id),
    analysis_job_id uuid NOT NULL UNIQUE,
    membership_version bigint NOT NULL CHECK (membership_version > 0),
    analysis_version text NOT NULL CHECK (length(trim(analysis_version)) BETWEEN 1 AND 100),
    published_at timestamptz NOT NULL DEFAULT now(),
    roles jsonb NOT NULL CHECK (jsonb_typeof(roles) = 'array'),
    relations jsonb NOT NULL CHECK (jsonb_typeof(relations) = 'array'),
    coverage jsonb NOT NULL CHECK (jsonb_typeof(coverage) = 'array'),
    access_state text NOT NULL DEFAULT 'ACTIVE' CHECK (access_state IN ('ACTIVE','BLOCKED','DELETE_PENDING')),
    revocation_id uuid,
    UNIQUE (id, project_id),
    FOREIGN KEY (analysis_job_id, project_id) REFERENCES project.analysis_job(id, project_id)
);

CREATE TABLE project.snapshot_member (
    id uuid PRIMARY KEY,
    snapshot_id uuid NOT NULL,
    project_id uuid NOT NULL,
    project_repository_id uuid NOT NULL,
    github_repository_id text NOT NULL,
    commit_sha text NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    visibility_at_analysis text NOT NULL CHECK (visibility_at_analysis = 'PUBLIC'),
    UNIQUE (snapshot_id, project_repository_id),
    UNIQUE (id, snapshot_id, github_repository_id, commit_sha),
    FOREIGN KEY (snapshot_id, project_id) REFERENCES project.analysis_snapshot(id, project_id),
    FOREIGN KEY (project_repository_id, project_id, github_repository_id)
        REFERENCES project.project_repository(id, project_id, github_repository_id)
);
CREATE INDEX snapshot_member_repository ON project.snapshot_member(github_repository_id, snapshot_id);

CREATE TABLE project.evidence_reference (
    id uuid PRIMARY KEY,
    snapshot_id uuid NOT NULL,
    snapshot_member_id uuid NOT NULL,
    github_repository_id text NOT NULL,
    commit_sha text NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    file_path text NOT NULL CHECK (length(file_path) BETWEEN 1 AND 1024 AND length(trim(file_path)) > 0
        AND file_path !~ '(^/|/$|//|(^|/)\.{1,2}(/|$)|[[:cntrl:]]|:)' AND strpos(file_path, chr(92)) = 0),
    line_start integer,
    line_end integer,
    claim_type text NOT NULL CHECK (claim_type IN ('CODE_FACT','DOC_CLAIM','HYPOTHESIS')),
    summary text NOT NULL CHECK (length(trim(summary)) BETWEEN 1 AND 1000),
    CHECK ((line_start IS NULL AND line_end IS NULL) OR
        (line_start IS NOT NULL AND line_end IS NOT NULL AND line_start > 0 AND line_end >= line_start)),
    UNIQUE (id, snapshot_id),
    FOREIGN KEY (snapshot_member_id, snapshot_id, github_repository_id, commit_sha)
        REFERENCES project.snapshot_member(id, snapshot_id, github_repository_id, commit_sha)
);
CREATE INDEX evidence_reference_snapshot ON project.evidence_reference(snapshot_id);

CREATE TABLE project.available_topic (
    id uuid PRIMARY KEY,
    snapshot_id uuid NOT NULL REFERENCES project.analysis_snapshot(id),
    topic_key text NOT NULL CHECK (topic_key ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    is_active boolean NOT NULL,
    primary_evidence_id uuid,
    CHECK (NOT is_active OR primary_evidence_id IS NOT NULL),
    UNIQUE (snapshot_id, topic_key),
    FOREIGN KEY (primary_evidence_id, snapshot_id) REFERENCES project.evidence_reference(id, snapshot_id)
);

ALTER TABLE project.registered_project ADD COLUMN latest_ready_snapshot_id uuid,
    ADD CONSTRAINT registered_project_latest_snapshot_fk
        FOREIGN KEY (latest_ready_snapshot_id, id) REFERENCES project.analysis_snapshot(id, project_id);

CREATE FUNCTION project.reject_snapshot_body_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME <> 'analysis_snapshot' OR
        (to_jsonb(NEW) - 'access_state' - 'revocation_id') IS DISTINCT FROM
        (to_jsonb(OLD) - 'access_state' - 'revocation_id') THEN
        RAISE EXCEPTION 'snapshot body is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER analysis_snapshot_immutable BEFORE UPDATE ON project.analysis_snapshot
    FOR EACH ROW EXECUTE FUNCTION project.reject_snapshot_body_update();
CREATE TRIGGER snapshot_member_immutable BEFORE UPDATE ON project.snapshot_member
    FOR EACH ROW EXECUTE FUNCTION project.reject_snapshot_body_update();
CREATE TRIGGER evidence_reference_immutable BEFORE UPDATE ON project.evidence_reference
    FOR EACH ROW EXECUTE FUNCTION project.reject_snapshot_body_update();
CREATE TRIGGER available_topic_immutable BEFORE UPDATE ON project.available_topic
    FOR EACH ROW EXECUTE FUNCTION project.reject_snapshot_body_update();

CREATE FUNCTION project.reject_published_snapshot_append() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM project.analysis_snapshot s JOIN project.analysis_job j ON j.id=s.analysis_job_id
                   WHERE s.id=NEW.snapshot_id AND j.state='RUNNING') THEN
        RAISE EXCEPTION 'published snapshot cannot be extended';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER snapshot_member_no_append BEFORE INSERT ON project.snapshot_member
    FOR EACH ROW EXECUTE FUNCTION project.reject_published_snapshot_append();
CREATE TRIGGER evidence_reference_no_append BEFORE INSERT ON project.evidence_reference
    FOR EACH ROW EXECUTE FUNCTION project.reject_published_snapshot_append();
CREATE TRIGGER available_topic_no_append BEFORE INSERT ON project.available_topic
    FOR EACH ROW EXECUTE FUNCTION project.reject_published_snapshot_append();
