-- Accounts (Phase 7): who can log in, and the refresh tokens of their sessions.
-- Emails are stored lowercased, so the unique constraint ignores case.
CREATE TABLE account (
    id             uuid         NOT NULL,
    email          varchar(254) NOT NULL,
    password_hash  varchar(200) NOT NULL,
    created_at     timestamptz  NOT NULL,
    failed_logins  integer      NOT NULL DEFAULT 0,
    last_failed_at timestamptz,
    locked_until   timestamptz,
    CONSTRAINT account_pkey PRIMARY KEY (id),
    CONSTRAINT account_email_key UNIQUE (email),
    CONSTRAINT account_email_check CHECK (email = lower(email)),
    CONSTRAINT account_failed_logins_check CHECK (failed_logins >= 0)
);

-- Every refresh token issued, kept until it expires: a token presented again after it was
-- exchanged reveals a stolen copy, so used tokens stay as long as they could be replayed.
-- A family is one login; rotation keeps the family, and ending a session revokes all of it.
-- Only the SHA-256 of a token is stored.
CREATE TABLE refresh_token (
    token_hash         char(64)    NOT NULL,
    account_id         uuid        NOT NULL,
    family             uuid        NOT NULL,
    issued_at          timestamptz NOT NULL,
    expires_at         timestamptz NOT NULL,
    session_expires_at timestamptz NOT NULL,
    used_at            timestamptz,
    revoked_at         timestamptz,
    CONSTRAINT refresh_token_pkey PRIMARY KEY (token_hash),
    CONSTRAINT refresh_token_account_id_fkey FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE CASCADE,
    CONSTRAINT refresh_token_expiry_check CHECK (expires_at > issued_at AND expires_at <= session_expires_at)
);

-- Deleting an account deletes its tokens; logging out revokes a family; the retention job
-- deletes expired tokens.
CREATE INDEX refresh_token_account_id_idx ON refresh_token (account_id);
CREATE INDEX refresh_token_family_idx ON refresh_token (family);
CREATE INDEX refresh_token_expires_at_idx ON refresh_token (expires_at);
