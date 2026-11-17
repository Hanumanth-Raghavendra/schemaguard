-- Saved analyses: the request and the result exactly as they were sent and returned, so a
-- permalink keeps showing what was computed even after the engine changes.
-- json, not jsonb: the text is never queried, and json keeps it byte for byte.
CREATE TABLE analysis (
    id                varchar(32)  NOT NULL,
    kind              varchar(16)  NOT NULL,
    request           json         NOT NULL,
    result            json         NOT NULL,
    engine_version    varchar(64)  NOT NULL,
    size_bytes        bigint       NOT NULL,
    delete_token_hash char(64)     NOT NULL,
    created_at        timestamptz  NOT NULL,
    expires_at        timestamptz  NOT NULL,
    CONSTRAINT analysis_pkey PRIMARY KEY (id),
    CONSTRAINT analysis_kind_check CHECK (kind IN ('diff', 'report', 'plan', 'lint')),
    CONSTRAINT analysis_size_check CHECK (size_bytes > 0),
    CONSTRAINT analysis_expiry_check CHECK (expires_at > created_at)
);

-- The retention job and every lookup filter on expiry.
CREATE INDEX analysis_expires_at_idx ON analysis (expires_at);
