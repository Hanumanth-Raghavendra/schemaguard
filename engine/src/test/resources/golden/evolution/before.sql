-- A small publishing app at version 1. migration.sql takes it to version 2. The fixture holds
-- the pg_dump of the database before and after, and the diff SchemaGuard reports between them.

CREATE SCHEMA app;

CREATE TYPE app.status AS ENUM ('draft', 'published', 'archived');
CREATE TYPE app.role AS ENUM ('reader', 'writer', 'admin');

CREATE TABLE app.users (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email varchar(255) NOT NULL UNIQUE,
    display_name text,
    role app.role NOT NULL DEFAULT 'reader',
    bio text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app.posts (
    id bigserial PRIMARY KEY,
    author_id bigint NOT NULL REFERENCES app.users,
    title varchar(120) NOT NULL,
    body text,
    status app.status NOT NULL DEFAULT 'draft',
    views integer DEFAULT 0 CHECK (views >= 0),
    published_at timestamptz
);
CREATE INDEX posts_author_idx ON app.posts (author_id);
CREATE INDEX posts_status_idx ON app.posts (status);
CREATE INDEX posts_title_idx ON app.posts (lower(title));

CREATE TABLE app.tags (
    id serial PRIMARY KEY,
    name text NOT NULL UNIQUE
);

CREATE TABLE app.post_tags (
    post_id bigint NOT NULL REFERENCES app.posts ON DELETE CASCADE,
    tag_id integer NOT NULL REFERENCES app.tags,
    PRIMARY KEY (post_id, tag_id)
);

CREATE TABLE app.audit_log (
    id bigserial PRIMARY KEY,
    actor bigint,
    action text NOT NULL,
    at timestamptz DEFAULT now()
);

CREATE SEQUENCE app.invoice_number START 1000;
