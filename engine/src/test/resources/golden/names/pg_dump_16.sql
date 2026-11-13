--
-- PostgreSQL database dump
--

\restrict ReZwI2tTeqQLizqFrqRJrrSbfBixvQmGXriJdDov3UVAswLk6WGabAj55lxQaze

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
-- Name: mood; Type: TYPE; Schema: public; Owner: postgres
--

CREATE TYPE public.mood AS ENUM (
    'sad',
    'ok',
    'happy'
);


ALTER TYPE public.mood OWNER TO postgres;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: Quoted Name; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public."Quoted Name" (
    "Key" integer NOT NULL,
    "Other Col" integer,
    ref integer,
    CONSTRAINT "Quoted Name_Other Col_check" CHECK (("Other Col" > 0))
);


ALTER TABLE public."Quoted Name" OWNER TO postgres;

--
-- Name: a_table_with_a_really_long_name_that_goes_on_and_on_and_on_1234; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.a_table_with_a_really_long_name_that_goes_on_and_on_and_on_1234 (
    a_column_with_a_really_long_name_that_goes_on_and_on_and_on_567 integer NOT NULL,
    short integer,
    another_quite_long_column_name_to_push_things_over_the_limit_xx integer,
    CONSTRAINT a_table_with_a_really_long_name_that_goes_on_and_on_short_check CHECK ((short > 0))
);


ALTER TABLE public.a_table_with_a_really_long_name_that_goes_on_and_on_and_on_1234 OWNER TO postgres;

--
-- Name: a_table_with_a_really_long_na_a_column_with_a_really_long_n_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.a_table_with_a_really_long_na_a_column_with_a_really_long_n_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.a_table_with_a_really_long_na_a_column_with_a_really_long_n_seq OWNER TO postgres;

--
-- Name: a_table_with_a_really_long_na_a_column_with_a_really_long_n_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.a_table_with_a_really_long_na_a_column_with_a_really_long_n_seq OWNED BY public.a_table_with_a_really_long_name_that_goes_on_and_on_and_on_1234.a_column_with_a_really_long_name_that_goes_on_and_on_and_on_567;


--
-- Name: child; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.child (
    id bigint NOT NULL,
    small smallint NOT NULL,
    parent_id integer,
    other_id integer,
    parent_id2 integer,
    m public.mood DEFAULT 'ok'::public.mood,
    x integer,
    y integer,
    CONSTRAINT child_check CHECK ((x < y)),
    CONSTRAINT child_check1 CHECK (((x + y) > 0)),
    CONSTRAINT child_x_check CHECK ((x > 0)),
    CONSTRAINT child_x_check1 CHECK ((x < 100)),
    CONSTRAINT child_y_check CHECK ((y > 0))
);


ALTER TABLE public.child OWNER TO postgres;

--
-- Name: child_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.child_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.child_id_seq OWNER TO postgres;

--
-- Name: child_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.child_id_seq OWNED BY public.child.id;


--
-- Name: child_small_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.child_small_seq
    AS smallint
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.child_small_seq OWNER TO postgres;

--
-- Name: child_small_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.child_small_seq OWNED BY public.child.small;


--
-- Name: parent; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.parent (
    id integer NOT NULL,
    code text,
    a integer,
    b integer,
    c integer
);


ALTER TABLE public.parent OWNER TO postgres;

--
-- Name: parent_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.parent_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.parent_id_seq OWNER TO postgres;

--
-- Name: parent_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.parent_id_seq OWNED BY public.parent.id;


--
-- Name: merged; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.merged (
    id integer NOT NULL,
    v integer,
    w integer,
    seq_default bigint DEFAULT nextval('public.parent_id_seq'::regclass)
);


ALTER TABLE public.merged OWNER TO postgres;

--
-- Name: u; Type: TABLE; Schema: public; Owner: postgres
--

CREATE UNLOGGED TABLE public.u (
    id integer NOT NULL
);


ALTER TABLE public.u OWNER TO postgres;

--
-- Name: u_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE UNLOGGED SEQUENCE public.u_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.u_id_seq OWNER TO postgres;

--
-- Name: u_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.u_id_seq OWNED BY public.u.id;


--
-- Name: a_table_with_a_really_long_name_that_goes_on_and_on_and_on_1234 a_column_with_a_really_long_name_that_goes_on_and_on_and_on_567; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.a_table_with_a_really_long_name_that_goes_on_and_on_and_on_1234 ALTER COLUMN a_column_with_a_really_long_name_that_goes_on_and_on_and_on_567 SET DEFAULT nextval('public.a_table_with_a_really_long_na_a_column_with_a_really_long_n_seq'::regclass);


--
-- Name: child id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.child ALTER COLUMN id SET DEFAULT nextval('public.child_id_seq'::regclass);


--
-- Name: child small; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.child ALTER COLUMN small SET DEFAULT nextval('public.child_small_seq'::regclass);


--
-- Name: parent id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.parent ALTER COLUMN id SET DEFAULT nextval('public.parent_id_seq'::regclass);


--
-- Name: u id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.u ALTER COLUMN id SET DEFAULT nextval('public.u_id_seq'::regclass);


--
-- Name: Quoted Name Quoted Name_Other Col_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public."Quoted Name"
    ADD CONSTRAINT "Quoted Name_Other Col_key" UNIQUE ("Other Col");


--
-- Name: Quoted Name Quoted Name_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public."Quoted Name"
    ADD CONSTRAINT "Quoted Name_pkey" PRIMARY KEY ("Key");


--
-- Name: a_table_with_a_really_long_name_that_goes_on_and_on_and_on_1234 a_table_with_a_really_long_name_that_goes_on_and_on_a_short_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.a_table_with_a_really_long_name_that_goes_on_and_on_and_on_1234
    ADD CONSTRAINT a_table_with_a_really_long_name_that_goes_on_and_on_a_short_key UNIQUE (short);


--
-- Name: a_table_with_a_really_long_name_that_goes_on_and_on_and_on_1234 a_table_with_a_really_long_name_that_goes_on_and_on_and_on_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.a_table_with_a_really_long_name_that_goes_on_and_on_and_on_1234
    ADD CONSTRAINT a_table_with_a_really_long_name_that_goes_on_and_on_and_on_pkey PRIMARY KEY (a_column_with_a_really_long_name_that_goes_on_and_on_and_on_567);


--
-- Name: merged merged_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.merged
    ADD CONSTRAINT merged_pkey PRIMARY KEY (id);


--
-- Name: merged named_v; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.merged
    ADD CONSTRAINT named_v UNIQUE (v);


--
-- Name: merged named_w; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.merged
    ADD CONSTRAINT named_w UNIQUE (w);


--
-- Name: parent parent_a_b_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.parent
    ADD CONSTRAINT parent_a_b_key UNIQUE (a, b);


--
-- Name: parent parent_c_a_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.parent
    ADD CONSTRAINT parent_c_a_key UNIQUE (c) INCLUDE (a);


--
-- Name: parent parent_code_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.parent
    ADD CONSTRAINT parent_code_key UNIQUE (code);


--
-- Name: parent parent_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.parent
    ADD CONSTRAINT parent_pkey PRIMARY KEY (id);


--
-- Name: Quoted Name_Other Col_ref_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX "Quoted Name_Other Col_ref_idx" ON public."Quoted Name" USING btree ("Other Col", ref);


--
-- Name: a_table_with_a_really_long_na_a_column_with_a_really_long_n_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX a_table_with_a_really_long_na_a_column_with_a_really_long_n_idx ON public.a_table_with_a_really_long_name_that_goes_on_and_on_and_on_1234 USING btree (a_column_with_a_really_long_name_that_goes_on_and_on_and_on_567, short);


--
-- Name: a_table_with_a_really_long_na_short_another_quite_long_colu_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX a_table_with_a_really_long_na_short_another_quite_long_colu_idx ON public.a_table_with_a_really_long_name_that_goes_on_and_on_and_on_1234 USING btree (short, another_quite_long_column_name_to_push_things_over_the_limit_xx);


--
-- Name: child_parent_id_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX child_parent_id_idx ON public.child USING btree (parent_id);


--
-- Name: child_parent_id_idx1; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX child_parent_id_idx1 ON public.child USING btree (parent_id);


--
-- Name: child_x_y_m_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX child_x_y_m_idx ON public.child USING btree (x, y DESC) INCLUDE (m);


--
-- Name: child_y_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE UNIQUE INDEX child_y_idx ON public.child USING btree (y) WHERE (x IS NOT NULL);


--
-- Name: parent_lower_expr_expr1_code_coalesce_case_abs_abs1_a_expr2_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX parent_lower_expr_expr1_code_coalesce_case_abs_abs1_a_expr2_idx ON public.parent USING btree (lower(code), ((a + b)), ((a - b)), ((code)::character varying), COALESCE(a, b), (
CASE
    WHEN (a > 0) THEN 1
    ELSE 0
END), abs(a), abs(b), a, ((code || 'x'::text)), ((- a)), NULLIF(a, b), GREATEST(a, b));


--
-- Name: Quoted Name Quoted Name_ref_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public."Quoted Name"
    ADD CONSTRAINT "Quoted Name_ref_fkey" FOREIGN KEY (ref) REFERENCES public."Quoted Name"("Key");


--
-- Name: a_table_with_a_really_long_name_that_goes_on_and_on_and_on_1234 a_table_with_a_really_long_na_another_quite_long_column_na_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.a_table_with_a_really_long_name_that_goes_on_and_on_and_on_1234
    ADD CONSTRAINT a_table_with_a_really_long_na_another_quite_long_column_na_fkey FOREIGN KEY (another_quite_long_column_name_to_push_things_over_the_limit_xx) REFERENCES public.parent(id);


--
-- Name: child child_other_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.child
    ADD CONSTRAINT child_other_id_fkey FOREIGN KEY (other_id) REFERENCES public.parent(id);


--
-- Name: child child_parent_id2_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.child
    ADD CONSTRAINT child_parent_id2_fkey FOREIGN KEY (parent_id2) REFERENCES public.parent(id) ON DELETE SET NULL;


--
-- Name: child child_parent_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.child
    ADD CONSTRAINT child_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES public.parent(id);


--
-- Name: child child_parent_id_fkey1; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.child
    ADD CONSTRAINT child_parent_id_fkey1 FOREIGN KEY (parent_id) REFERENCES public.parent(id);


--
-- Name: child child_x_y_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.child
    ADD CONSTRAINT child_x_y_fkey FOREIGN KEY (x, y) REFERENCES public.parent(a, b);


--
-- PostgreSQL database dump complete
--

\unrestrict ReZwI2tTeqQLizqFrqRJrrSbfBixvQmGXriJdDov3UVAswLk6WGabAj55lxQaze

