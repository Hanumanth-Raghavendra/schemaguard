-- Projects (Phase 7.2): an account's named schemas, each a numbered series of versions.
CREATE TABLE project (
    id         uuid        NOT NULL,
    account_id uuid        NOT NULL,
    name       varchar(80) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT project_pkey PRIMARY KEY (id),
    CONSTRAINT project_account_id_fkey FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE CASCADE,
    CONSTRAINT project_name_check CHECK (char_length(name) BETWEEN 1 AND 80)
);

-- Names are unique per account whatever their case. The index also serves the foreign key and
-- every "this account's projects" query.
CREATE UNIQUE INDEX project_account_id_name_key ON project (account_id, lower(name));

-- One row per saved version. The risk of the change from the version before is stored as it was
-- scored when saved (null for version 1, which has nothing before it), so the timeline needs no
-- re-analysis; opening a version re-analyses it with the current rules.
CREATE TABLE schema_version (
    project_id   uuid         NOT NULL,
    number       integer      NOT NULL,
    label        varchar(120),
    schema_sql   text         NOT NULL,
    size_bytes   integer      NOT NULL,
    created_at   timestamptz  NOT NULL,
    risk_score   smallint,
    risk_level   varchar(8),
    risk_reasons text[],
    changes      integer,
    findings     integer,
    CONSTRAINT schema_version_pkey PRIMARY KEY (project_id, number),
    CONSTRAINT schema_version_project_id_fkey FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT schema_version_number_check CHECK (number >= 1),
    CONSTRAINT schema_version_size_check CHECK (size_bytes >= 0),
    CONSTRAINT schema_version_risk_check CHECK (
        (number = 1 AND risk_score IS NULL AND risk_level IS NULL AND changes IS NULL AND findings IS NULL)
        OR (number > 1 AND risk_score BETWEEN 0 AND 100 AND risk_level IS NOT NULL AND changes >= 0 AND findings >= 0))
);
