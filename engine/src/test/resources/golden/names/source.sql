-- Objects whose names PostgreSQL has to choose: unnamed constraints and indexes, serial
-- sequences, repeated names that need a numeric suffix, and names long enough to be shortened.

CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy');

CREATE TABLE parent (
    id serial PRIMARY KEY,
    code text UNIQUE,
    a int,
    b int,
    c int,
    UNIQUE (a, b),
    UNIQUE (c) INCLUDE (a)
);

CREATE TABLE child (
    id bigserial,
    small smallserial,
    parent_id int REFERENCES parent,
    other_id int REFERENCES parent (id),
    parent_id2 int,
    m mood DEFAULT 'ok',
    x int CHECK (x > 0) CHECK (x < 100),
    y int,
    CHECK (x < y),
    CHECK (y > 0),
    CHECK (x + y > 0),
    FOREIGN KEY (parent_id2) REFERENCES parent ON DELETE SET NULL,
    FOREIGN KEY (parent_id) REFERENCES parent (id),
    FOREIGN KEY (x, y) REFERENCES parent (a, b)
);

CREATE INDEX ON child (parent_id);
CREATE INDEX ON child (parent_id);
CREATE INDEX ON child (x, y DESC) INCLUDE (m);
CREATE UNIQUE INDEX ON child (y) WHERE x IS NOT NULL;
CREATE INDEX ON parent (lower(code), (a + b), (a - b), (code::varchar), coalesce(a, b), (CASE WHEN a > 0 THEN 1 ELSE 0 END), abs(a), abs(b), (a), (code || 'x'), (- a), nullif(a, b), greatest(a, b));

CREATE TABLE a_table_with_a_really_long_name_that_goes_on_and_on_and_on_1234 (
    a_column_with_a_really_long_name_that_goes_on_and_on_and_on_567 serial PRIMARY KEY,
    short int UNIQUE,
    another_quite_long_column_name_to_push_things_over_the_limit_xx int REFERENCES parent,
    CHECK (short > 0)
);
CREATE INDEX ON a_table_with_a_really_long_name_that_goes_on_and_on_and_on_1234 (a_column_with_a_really_long_name_that_goes_on_and_on_and_on_567, short);
CREATE INDEX ON a_table_with_a_really_long_name_that_goes_on_and_on_and_on_1234 (short, another_quite_long_column_name_to_push_things_over_the_limit_xx);

CREATE UNLOGGED TABLE u (id serial);

CREATE TABLE "Quoted Name" (
    "Key" int PRIMARY KEY,
    "Other Col" int UNIQUE CHECK ("Other Col" > 0),
    ref int REFERENCES "Quoted Name"
);
CREATE INDEX ON "Quoted Name" ("Other Col", ref);

-- Within one CREATE TABLE, a unique constraint that repeats an earlier key is not created
-- again, and passes its name on if the earlier key had none.
CREATE TABLE merged (
    id int PRIMARY KEY,
    UNIQUE (id),
    v int UNIQUE,
    UNIQUE (v),
    CONSTRAINT named_v UNIQUE (v),
    w int,
    CONSTRAINT named_w UNIQUE (w),
    UNIQUE (w),
    seq_default bigint DEFAULT nextval('parent_id_seq')
);
