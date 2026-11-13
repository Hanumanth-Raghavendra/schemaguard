--
-- PostgreSQL database dump
--

\restrict H7LKPupmdoKLbv532nere8xel4ok6KjbZakjG6dbrlqz3sdktHSzPBeYovrf4aP

-- Dumped from database version 16.15 (Ubuntu 16.15-0ubuntu0.24.04.1)
-- Dumped by pg_dump version 16.15 (Ubuntu 16.15-0ubuntu0.24.04.1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: app; Type: SCHEMA; Schema: -; Owner: postgres
--

CREATE SCHEMA app;


ALTER SCHEMA app OWNER TO postgres;

--
-- Name: role; Type: TYPE; Schema: app; Owner: postgres
--

CREATE TYPE app.role AS ENUM (
    'reader',
    'writer',
    'admin'
);


ALTER TYPE app.role OWNER TO postgres;

--
-- Name: status; Type: TYPE; Schema: app; Owner: postgres
--

CREATE TYPE app.status AS ENUM (
    'draft',
    'published',
    'archived'
);


ALTER TYPE app.status OWNER TO postgres;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: audit_log; Type: TABLE; Schema: app; Owner: postgres
--

CREATE TABLE app.audit_log (
    id bigint NOT NULL,
    actor bigint,
    action text NOT NULL,
    at timestamp with time zone DEFAULT now()
);


ALTER TABLE app.audit_log OWNER TO postgres;

--
-- Name: audit_log_id_seq; Type: SEQUENCE; Schema: app; Owner: postgres
--

CREATE SEQUENCE app.audit_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE app.audit_log_id_seq OWNER TO postgres;

--
-- Name: audit_log_id_seq; Type: SEQUENCE OWNED BY; Schema: app; Owner: postgres
--

ALTER SEQUENCE app.audit_log_id_seq OWNED BY app.audit_log.id;


--
-- Name: invoice_number; Type: SEQUENCE; Schema: app; Owner: postgres
--

CREATE SEQUENCE app.invoice_number
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE app.invoice_number OWNER TO postgres;

--
-- Name: post_tags; Type: TABLE; Schema: app; Owner: postgres
--

CREATE TABLE app.post_tags (
    post_id bigint NOT NULL,
    tag_id integer NOT NULL
);


ALTER TABLE app.post_tags OWNER TO postgres;

--
-- Name: posts; Type: TABLE; Schema: app; Owner: postgres
--

CREATE TABLE app.posts (
    id bigint NOT NULL,
    author_id bigint NOT NULL,
    title character varying(120) NOT NULL,
    body text,
    status app.status DEFAULT 'draft'::app.status NOT NULL,
    views integer DEFAULT 0,
    published_at timestamp with time zone,
    CONSTRAINT posts_views_check CHECK ((views >= 0))
);


ALTER TABLE app.posts OWNER TO postgres;

--
-- Name: posts_id_seq; Type: SEQUENCE; Schema: app; Owner: postgres
--

CREATE SEQUENCE app.posts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE app.posts_id_seq OWNER TO postgres;

--
-- Name: posts_id_seq; Type: SEQUENCE OWNED BY; Schema: app; Owner: postgres
--

ALTER SEQUENCE app.posts_id_seq OWNED BY app.posts.id;


--
-- Name: tags; Type: TABLE; Schema: app; Owner: postgres
--

CREATE TABLE app.tags (
    id integer NOT NULL,
    name text NOT NULL
);


ALTER TABLE app.tags OWNER TO postgres;

--
-- Name: tags_id_seq; Type: SEQUENCE; Schema: app; Owner: postgres
--

CREATE SEQUENCE app.tags_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE app.tags_id_seq OWNER TO postgres;

--
-- Name: tags_id_seq; Type: SEQUENCE OWNED BY; Schema: app; Owner: postgres
--

ALTER SEQUENCE app.tags_id_seq OWNED BY app.tags.id;


--
-- Name: users; Type: TABLE; Schema: app; Owner: postgres
--

CREATE TABLE app.users (
    id bigint NOT NULL,
    email character varying(255) NOT NULL,
    display_name text,
    role app.role DEFAULT 'reader'::app.role NOT NULL,
    bio text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE app.users OWNER TO postgres;

--
-- Name: users_id_seq; Type: SEQUENCE; Schema: app; Owner: postgres
--

ALTER TABLE app.users ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME app.users_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: audit_log id; Type: DEFAULT; Schema: app; Owner: postgres
--

ALTER TABLE ONLY app.audit_log ALTER COLUMN id SET DEFAULT nextval('app.audit_log_id_seq'::regclass);


--
-- Name: posts id; Type: DEFAULT; Schema: app; Owner: postgres
--

ALTER TABLE ONLY app.posts ALTER COLUMN id SET DEFAULT nextval('app.posts_id_seq'::regclass);


--
-- Name: tags id; Type: DEFAULT; Schema: app; Owner: postgres
--

ALTER TABLE ONLY app.tags ALTER COLUMN id SET DEFAULT nextval('app.tags_id_seq'::regclass);


--
-- Name: audit_log audit_log_pkey; Type: CONSTRAINT; Schema: app; Owner: postgres
--

ALTER TABLE ONLY app.audit_log
    ADD CONSTRAINT audit_log_pkey PRIMARY KEY (id);


--
-- Name: post_tags post_tags_pkey; Type: CONSTRAINT; Schema: app; Owner: postgres
--

ALTER TABLE ONLY app.post_tags
    ADD CONSTRAINT post_tags_pkey PRIMARY KEY (post_id, tag_id);


--
-- Name: posts posts_pkey; Type: CONSTRAINT; Schema: app; Owner: postgres
--

ALTER TABLE ONLY app.posts
    ADD CONSTRAINT posts_pkey PRIMARY KEY (id);


--
-- Name: tags tags_name_key; Type: CONSTRAINT; Schema: app; Owner: postgres
--

ALTER TABLE ONLY app.tags
    ADD CONSTRAINT tags_name_key UNIQUE (name);


--
-- Name: tags tags_pkey; Type: CONSTRAINT; Schema: app; Owner: postgres
--

ALTER TABLE ONLY app.tags
    ADD CONSTRAINT tags_pkey PRIMARY KEY (id);


--
-- Name: users users_email_key; Type: CONSTRAINT; Schema: app; Owner: postgres
--

ALTER TABLE ONLY app.users
    ADD CONSTRAINT users_email_key UNIQUE (email);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: app; Owner: postgres
--

ALTER TABLE ONLY app.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: posts_author_idx; Type: INDEX; Schema: app; Owner: postgres
--

CREATE INDEX posts_author_idx ON app.posts USING btree (author_id);


--
-- Name: posts_status_idx; Type: INDEX; Schema: app; Owner: postgres
--

CREATE INDEX posts_status_idx ON app.posts USING btree (status);


--
-- Name: posts_title_idx; Type: INDEX; Schema: app; Owner: postgres
--

CREATE INDEX posts_title_idx ON app.posts USING btree (lower((title)::text));


--
-- Name: post_tags post_tags_post_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: postgres
--

ALTER TABLE ONLY app.post_tags
    ADD CONSTRAINT post_tags_post_id_fkey FOREIGN KEY (post_id) REFERENCES app.posts(id) ON DELETE CASCADE;


--
-- Name: post_tags post_tags_tag_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: postgres
--

ALTER TABLE ONLY app.post_tags
    ADD CONSTRAINT post_tags_tag_id_fkey FOREIGN KEY (tag_id) REFERENCES app.tags(id);


--
-- Name: posts posts_author_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: postgres
--

ALTER TABLE ONLY app.posts
    ADD CONSTRAINT posts_author_id_fkey FOREIGN KEY (author_id) REFERENCES app.users(id);


--
-- PostgreSQL database dump complete
--

\unrestrict H7LKPupmdoKLbv532nere8xel4ok6KjbZakjG6dbrlqz3sdktHSzPBeYovrf4aP

