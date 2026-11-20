/** A scenario of the example gallery: a schema as it is, and the same schema after a common change. */
export interface Example {
  id: string;
  title: string;
  summary: string;
  before: string;
  after: string;
}

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

const USERS = `CREATE TABLE users (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  email text NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT now()
);
`;

/** Real-world changes, each loaded and analysed in one click. */
export const EXAMPLES: Example[] = [
  {
    id: 'release',
    title: 'A typical release',
    summary: 'A rename, a wider id, a new foreign key and an index in one go',
    before: EXAMPLE_BEFORE,
    after: EXAMPLE_AFTER,
  },
  {
    id: 'required-column',
    title: 'Add a required column',
    summary: 'NOT NULL with a constant default is instant; without one it fails on a filled table',
    before: USERS,
    after: `CREATE TABLE users (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  email text NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT now(),
  country text NOT NULL DEFAULT 'IN',
  last_seen_at timestamptz NOT NULL
);
`,
  },
  {
    id: 'foreign-key',
    title: 'Link two tables',
    summary: 'A new foreign key checks every row while writes to both tables wait',
    before: `${USERS}
CREATE TABLE posts (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id bigint NOT NULL,
  body text NOT NULL
);
`,
    after: `${USERS}
CREATE TABLE posts (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id bigint NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  body text NOT NULL
);
`,
  },
  {
    id: 'bigint',
    title: 'Widen an id to bigint',
    summary: 'The key is running out of integers; changing its type rewrites the table',
    before: `CREATE TABLE events (
  id integer PRIMARY KEY,
  kind text NOT NULL,
  payload jsonb NOT NULL,
  happened_at timestamptz NOT NULL
);
`,
    after: `CREATE TABLE events (
  id bigint PRIMARY KEY,
  kind text NOT NULL,
  payload jsonb NOT NULL,
  happened_at timestamptz NOT NULL
);
`,
  },
  {
    id: 'rename',
    title: 'Rename a column',
    summary: 'Is it a rename or a drop and an add? Confirm it in the plan to keep the data',
    before: `CREATE TABLE products (
  id bigint PRIMARY KEY,
  title text NOT NULL,
  price_cents integer NOT NULL
);
`,
    after: `CREATE TABLE products (
  id bigint PRIMARY KEY,
  product_title text NOT NULL,
  price_cents integer NOT NULL
);
`,
  },
  {
    id: 'index',
    title: 'Index a busy table',
    summary: 'A plain CREATE INDEX blocks writes until it finishes; CONCURRENTLY does not',
    before: `CREATE TABLE orders (
  id bigint PRIMARY KEY,
  customer_id bigint NOT NULL,
  reference text NOT NULL,
  placed_at timestamptz NOT NULL
);
`,
    after: `CREATE TABLE orders (
  id bigint PRIMARY KEY,
  customer_id bigint NOT NULL,
  reference text NOT NULL UNIQUE,
  placed_at timestamptz NOT NULL
);

CREATE INDEX orders_customer_placed_idx ON orders (customer_id, placed_at);
`,
  },
  {
    id: 'split',
    title: 'Split a table',
    summary: 'Addresses move out of customers into their own table',
    before: `CREATE TABLE customers (
  id bigint PRIMARY KEY,
  email text NOT NULL,
  street text,
  city text,
  postcode text
);
`,
    after: `CREATE TABLE customers (
  id bigint PRIMARY KEY,
  email text NOT NULL
);

CREATE TABLE addresses (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  customer_id bigint NOT NULL REFERENCES customers (id),
  street text NOT NULL,
  city text NOT NULL,
  postcode text NOT NULL
);
`,
  },
];
