/** A small shop schema and a typical next release of it: a rename, a type change, a foreign key, an index. */
export const EXAMPLE_BEFORE = `CREATE TABLE customers (
  id integer PRIMARY KEY,
  email text NOT NULL,
  name text
);

CREATE TABLE orders (
  id integer PRIMARY KEY,
  customer_id integer NOT NULL,
  status text NOT NULL,
  total numeric(10, 2)
);
`;

export const EXAMPLE_AFTER = `CREATE TABLE customers (
  id integer PRIMARY KEY,
  email text NOT NULL,
  full_name text,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE orders (
  id bigint PRIMARY KEY,
  customer_id integer NOT NULL REFERENCES customers (id),
  status text NOT NULL,
  total numeric(12, 2)
);

CREATE INDEX orders_status_idx ON orders (status);
`;

/** The same release written by hand as a migration script. */
export const EXAMPLE_MIGRATION = `ALTER TABLE customers RENAME COLUMN name TO full_name;
ALTER TABLE customers ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE orders ALTER COLUMN id TYPE bigint;
ALTER TABLE orders ADD FOREIGN KEY (customer_id) REFERENCES customers (id);
CREATE INDEX orders_status_idx ON orders (status);
`;
