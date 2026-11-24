-- API keys (Phase 7.4): how the command-line tool and CI read projects and save versions without
-- a login. Only the SHA-256 of a key is stored, with its first characters as a hint to tell keys apart.
CREATE TABLE api_key (
    id           uuid        NOT NULL,
    account_id   uuid        NOT NULL,
    name         varchar(80) NOT NULL,
    hint         varchar(16) NOT NULL,
    key_hash     char(64)    NOT NULL,
    created_at   timestamptz NOT NULL,
    last_used_at timestamptz,
    CONSTRAINT api_key_pkey PRIMARY KEY (id),
    CONSTRAINT api_key_account_id_fkey FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE CASCADE,
    CONSTRAINT api_key_key_hash_key UNIQUE (key_hash),
    CONSTRAINT api_key_name_check CHECK (char_length(name) BETWEEN 1 AND 80)
);

CREATE INDEX api_key_account_id_idx ON api_key (account_id);
