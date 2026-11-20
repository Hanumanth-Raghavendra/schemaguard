# Interview notes

What was built in each phase, why it was built that way, and the questions it invites.
Read the code next to these notes; you should be able to explain every line.

## Phase 1.2: the lexer

**What it is.** `Lexer.tokenize(sql)` turns SQL text into a list of tokens (`WORD`, `STRING`,
`NUMBER`, `OPERATOR`, punctuation) plus a list of diagnostics. It is a single pass over the
characters with one character of lookahead (two in a few places), so it runs in O(n).

**Design choices worth explaining**

1. **No keyword table.** In PostgreSQL most keywords are non-reserved: `name`, `type`, `status`
   are legal column names. Only context tells you which is which, so the lexer emits `WORD`
   and the parser decides. A lexer with a keyword list would mis-tokenize real schemas.
2. **Errors are data, not exceptions.** Every problem becomes a `Diagnostic` with a code
   (`SG-LEX-001`...), a message and a `SourceSpan` (offset, line, column). Lexing continues, so
   one unterminated string in a 5,000-line dump still yields every other finding.
3. **Case folding copies the server.** Unquoted identifiers fold to lower case, ASCII letters
   only, which is what PostgreSQL does in a UTF-8 database. `"Quoted"` identifiers keep their case.
   So `Users` and `users` are the same table, and `"Users"` is a different one.
4. **The operator rule.** A multi-character operator cannot end in `+` or `-` unless it contains
   one of ``~ ! @ # % ^ & | ` ?``. That is why `a=-1` means `a = -1`. The lexer takes the longest
   run of operator characters and then backs up (`rewind`) to honour the rule.
5. **Dollar quoting.** `$$ ... $$` and `$tag$ ... $tag$` bodies are taken verbatim. Without this,
   every `;` inside a function body would split the statement in the wrong place.
6. **Nested comments.** `/* a /* b */ c */` is one comment in PostgreSQL (not in C or Java), so
   the lexer keeps a depth counter.
7. **pg_dump awareness.** Lines such as `\connect` and `\restrict` are psql commands, and the
   rows after `COPY ... FROM stdin;` are data. A tiny state machine (`trackStatement`) spots
   the COPY form and skips to the `\.` terminator; each is emitted as one token for the parser to ignore.

**Questions you should be able to answer**

- Why write a lexer by hand instead of using a parser generator or a library? (D-004: control over
  coverage and error messages, zero dependencies, and SQL dialect libraries have gaps in `ALTER`.)
- What is the difference between a lexer and a parser? (Characters to tokens vs tokens to structure.)
- How do you know the lexer is correct? (67 tests, 99.7% line coverage, and a realistic pg_dump
  fixture that must lex with zero diagnostics. From phase 4, generated SQL is run on a real Postgres.)
- What does it not handle? (`U&` Unicode escapes, and string constants continued across lines.
  Both are reported or documented, not silently mis-read.)
- Why records? (Immutable value types with generated `equals`/`hashCode`; tokens never change after creation.)

## Phase 1.3 and 1.4a: the schema model and the CREATE TABLE parser

**What it is.** `Parser.parse(sql)` returns one `Statement` per SQL statement plus diagnostics.
`CREATE TABLE` becomes a `Table` made of immutable records (`Column`, `DataType`, and a sealed
`Constraint` family: `PrimaryKey`, `Unique`, `ForeignKey`, `Check`).

**How recursive descent works.** Each grammar rule is a method, and methods call each other the
way the grammar nests: `parseCreateTable` -> `parseTableElement` -> `parseColumn` ->
`TypeParser.parse` / `referencesClause` / `defaultExpression`. `TokenCursor` gives the lookahead:
`acceptWord("unique")` consumes a keyword if it is next, `acceptWords("primary", "key")` consumes
several only if all are present. There is no backtracking, so parsing is O(n) in the token count.

**Design choices worth explaining**

1. **Sealed interfaces + records + pattern-matching `switch`.** `Constraint` is sealed, so the
   compiler knows every subtype. A `switch` over a constraint needs no `default`, and adding a
   fifth constraint kind makes every such switch fail to compile until it is handled.
2. **Canonical types (D-012).** `int`, `int4` and `integer` all become `integer`; `char` becomes
   `character(1)`; `decimal(10)` becomes `numeric(10,0)`. Without this the diff engine would
   report differences that do not exist.
3. **Where a DEFAULT ends.** PostgreSQL's grammar restricts a default to a "b_expr", which cannot
   contain a bare `NOT`. So in `DEFAULT now() NOT NULL` the default is `now()`. The parser reads
   until a top-level keyword that starts another column constraint.
4. **Expressions as canonical text (D-011).** `TOTAL>=0` and `(( total >= 0 ))` both render as
   `total >= 0`. The renderer normalises case, spacing, quoting and redundant outer parentheses,
   and tells a unary minus (`-1`) from a binary one (`a - 1`) by looking at the previous token.
5. **Errors as control flow, contained (D-014).** A syntax error throws a private `ParseError`
   that is caught once per statement and turned into a diagnostic with a line and column. One
   bad statement never affects the next.
6. **Nothing is dropped silently.** Every statement becomes `CreateTable`, `Ignored` (known and
   irrelevant, such as `SET`), `Unsupported` (with a warning) or `Invalid` (with an error).
7. **The same checks the server makes.** Duplicate columns, two primary keys, and a key over a
   column that does not exist are all reported at parse time.

**Questions you should be able to answer**

- What is recursive descent, and what are its limits? (One method per rule, top-down. It cannot
  handle left-recursive grammars directly, which is why expressions are not parsed into a tree here.)
- Why records and sealed types instead of classes with setters? (Immutability: a parsed table can
  be shared and compared safely; `equals` comes for free, which the diff engine relies on.)
- How do you test a parser? (222 tests: a table of 50 type spellings, 31 syntax errors with exact
  messages, and a test that cuts a valid script at every character position and asserts the
  parser never throws.)
- Why not use an existing SQL parser library? (D-004.)
- What happens with SQL the parser does not understand? (A warning with the statement kind and
  position; the rest of the file is still analysed.)

## Phase 1.4b: indexes, sequences, enums, ALTER TABLE and DROP

**What it is.** The parser now understands every statement a `pg_dump` schema file is made of.
`Statement` has twelve variants; `ALTER TABLE` yields a list of `AlterAction`s (seventeen kinds).

**How the parser is organised (D-016).** `StatementParser` looks at the first few keywords and
dispatches. `ParserBase` holds the shared `TokenCursor` and the fragments many statements use
(`qualifiedName`, `columnList`, `ifExists`, `signedInteger`). `TableElementParser` parses columns
and constraints and is used by both `CREATE TABLE` and `ALTER TABLE ... ADD`. Because all of
them share one cursor, one parser can hand over to another in the middle of a statement.

**Design choices worth explaining**

1. **Actions, not results (D-017).** `ALTER TABLE t ALTER c SET NOT NULL` is stored as
   `SetNotNull("c")`, not as a changed table. Two migrations can end in the same schema and take
   very different locks on the way; the rules engine needs to see the path.
2. **Lookahead instead of backtracking.** `ADD CONSTRAINT x UNIQUE USING INDEX i` and
   `ADD CONSTRAINT x UNIQUE (a)` start the same way. `isConstraintUsingIndex()` peeks a few
   tokens ahead and decides before consuming anything, so the parser never has to undo work.
3. **Three kinds of index key.** A column (`email`), a function call (`lower(email)`) or any
   expression in parentheses (`(a + b)`). The parser tells them apart by what follows the first
   name: nothing, or an opening parenthesis.
4. **Defaults made explicit (D-018).** `CREATE INDEX i ON t (a)` and
   `CREATE INDEX i ON t USING btree (a ASC NULLS LAST)` produce equal `Index` records, because
   the defaults are filled in. A descending key defaults to NULLS FIRST, as in PostgreSQL.
5. **Where an expression ends.** `SET DEFAULT coalesce(a, 0), DROP COLUMN b`: the default ends
   at the first comma that is not inside parentheses or brackets, tracked with a depth counter.
6. **Signed numbers.** The lexer never produces a negative number; `-5` is an operator and a
   number, because `a-5` must be a subtraction. `signedInteger()` puts the sign back for
   `INCREMENT BY -5`, and handles `-9223372036854775808`, which only fits once the sign is attached.
7. **All or nothing (D-019).** If one action in an `ALTER TABLE` is not understood, the whole
   statement is reported as unsupported instead of being half-analysed.

**Questions you should be able to answer**

- Why is `AlterAction` a sealed interface of records? (The compiler checks every `switch` over
  actions is complete, so adding an eighteenth action cannot be forgotten in the catalog or rules.)
- Why does `ALTER TABLE` reuse the `CREATE TABLE` code? (Same grammar for column definitions and
  constraints; one implementation means one set of bugs and one set of tests.)
- How do you add support for a new statement? (A record in `Statement`, a parse method or class,
  one line in the dispatcher, tests. The compiler then points at every `switch` that must handle it.)
- What is `USING INDEX` for? (Building a unique index `CONCURRENTLY` and then promoting it is how
  a primary key or unique constraint is added without blocking writes. Phase 4 generates this.)
- What does the parser still not understand? (Views, functions, triggers, policies, partitions:
  each is reported by name with its position.)

## Phase 1.5 and 1.6: the catalog and the oracle tests

**What it is.** `CatalogBuilder.fromSql(script)` runs a script the way a server would run it on
an empty database and returns a `Catalog`: every table, index, sequence and enum, by name.
A migration tool needs this because its input is rarely one tidy `CREATE TABLE`; it is a
schema plus two years of `ALTER TABLE`.

**How it works.** `CatalogState` holds four sorted maps. For each statement the builder copies
the state, applies the statement, and on any error restores the copy and records a diagnostic
(D-020). `TableOperations` holds the rules: what a new column or constraint needs, what a
rename has to update, what a drop takes with it.

**Design choices worth explaining**

1. **The server is the specification (D-023).** Each fixture is a script plus the `pg_dump` of
   a real PostgreSQL 16 that ran the script. The test builds a catalog from both and requires
   them to be equal. That checks generated names, serial expansion, renames and cascades
   against what PostgreSQL actually did, not against what I assumed it does.
2. **It caught real mistakes.** Things I believed and the server corrected: a descending
   sequence prints `NO MAXVALUE` for -1; `id int PRIMARY KEY, UNIQUE (id)` creates one key, not
   two; a foreign key is tied to one specific key, so dropping that key needs CASCADE even if
   another key covers the same column; `ADD PRIMARY KEY (missing)` has a different error
   message from `ADD UNIQUE (missing)`.
3. **Generated names (D-022).** `t_pkey`, `t_a_b_key`, `t_a_fkey`, `t_a_check`, `t_a_idx`,
   `t_a_seq`. When a name would pass 63 characters, the longer of table and column is shortened
   one character at a time; a taken name gets a number after the label (`t_a_idx1`). This is a
   port of `makeObjectName` and `ChooseRelationName` from the PostgreSQL source.
4. **`serial` is not a type.** It expands into an integer column, NOT NULL, a sequence owned by
   the column, and a `nextval` default. The builder does that expansion, so `id serial` and the
   four statements `pg_dump` writes for it produce the same catalog.
5. **Dependencies on drop.** Dropping a column silently removes that table's constraints and
   indexes that use it. Things outside it, foreign keys in other tables and generated columns
   computed from it, need CASCADE; without it the statement fails, as in PostgreSQL.
6. **Atomic statements (D-020).** State values are immutable records, so "copy the state" is
   four shallow map copies and restoring is trivially correct. Immutability pays for itself here.
7. **Fingerprints (D-024).** `total >= 0` and `(total >= (0)::numeric)` are the same condition
   printed two ways. Removing parentheses and casts from both gives the same string. It can be
   fooled, since removing parentheses can hide a change in evaluation order, so the diff engine
   will report "probably cosmetic", never "identical".
8. **Messages in PostgreSQL's words (D-021).** All 73 error cases in `CatalogBuilderTest` were
   run against a real server and compared message by message.

**Questions you should be able to answer**

- What is an oracle test? (A test whose expected result comes from a trusted reference
  implementation, here PostgreSQL itself, instead of from the author.)
- Why build a catalog instead of diffing the SQL text? (Two scripts can be textually different
  and describe the same schema, or the reverse. Only the resulting schema can be compared.)
- How do you keep a failed statement from corrupting state? (Copy, apply, restore on error.
  Cheap because every value in the state is immutable.)
- How does `Catalog` equality work? (It is a record of sorted maps of records, and constraints
  are sorted by name, so `equals` is structural and independent of how the schema was written.)
- Where is this still approximate? (Expressions are text; see "Known issues" in PROGRESS.md.
  Each limit is listed, and none fails silently.)

## Phase 2: the diff engine and the SQL writer

**What it is.** `SchemaDiff.compare(before, after)` takes two catalogs and lists every
difference as a `Change`, one of 34 record types: tables, columns, constraints, indexes,
enum values and sequences, each added, dropped or changed. `ChangeFormatter` prints one line
per change. `SqlWriter` turns any model object back into PostgreSQL DDL.

Example, from the evolution fixture (two real `pg_dump` files):

```
~ column app.posts.views: type integer -> bigint
~ constraint app.posts.posts_views_check: renamed to posts_views_nonnegative
~ index app.posts_title_idx: renamed to posts_lower_title_idx
- column app.users.display_name text
+ column app.users.full_name text
? column app.users.display_name may have been renamed to full_name (confidence 0.65: same type and attributes; names 50% similar; both are column 3)
```

**How it works.** Objects are matched by name. Leftovers are matched by definition: a
constraint or index with an identical definition under a new name is a rename (D-028). A
first pass compares exactly, a second compares with expressions normalized, so a rename that
PostgreSQL also re-printed is still found. Everything still unmatched is a drop or an add.

**Design choices worth explaining**

1. **Certain versus probable renames (D-028, D-029).** An index is nothing but its definition,
   so "same definition, new name" is certainly a rename. Two columns with the same type are not
   certainly the same column: they may hold different data. So the diff reports the drop, the
   add and a scored candidate, and never decides. The cost of a wrong guess is data loss.
2. **Explainable scoring (D-030).** Confidence is built from three readable facts: same
   definition, name similarity, same position. The evidence is printed next to the number, so a
   user can see *why*. Edit distance alone fails on real renames (`email` to `email_address`
   is 8 edits), so similarity also checks word containment and abbreviation.
3. **Greedy one-to-one matching.** Candidates are sorted by confidence and taken greedily so no
   column is in two candidates. Optimal assignment (Hungarian algorithm) would be overkill:
   there are rarely more than a handful of candidates per table, and greedy is predictable.
4. **Cosmetic changes (D-031).** `total >= 0` and `(total >= (0)::numeric)` are flagged
   cosmetic, not hidden. `significant()` filters them; `changes()` keeps them.
5. **Literal diff (D-027).** The diff lists what differs; deciding what SQL to run, and what
   happens implicitly, belongs to the planner. Separation of concerns.
6. **Determinism.** Every collection is sorted, so the same input always gives the same
   output. That makes golden-file testing possible.
7. **Golden-file tests (D-032).** The whole diff of the evolution fixture is compared with a
   reviewed text file. Changing the output means regenerating the file and reviewing its diff.
8. **The writer is proven, not assumed (D-026).** For every fixture: catalog to script to
   catalog is exact through our parser, and PostgreSQL 16 accepts each script and dumps it
   back to an identical catalog. Phase 4 builds migration SQL on this.

**Questions you should be able to answer**

- How do you tell a rename from a drop plus an add? (Indexes and constraints: by definition,
  certain. Columns and tables: you can't be sure from the schema alone, so report a scored
  candidate with its evidence and let the user decide.)
- What is edit distance, and what is its complexity? (Levenshtein: fewest insertions,
  deletions and substitutions; dynamic programming, O(n x m) time, O(m) memory with two rows.)
- Why is the diff a list of typed records and not text? (The rules engine and planner need
  structure; text is only one rendering of it. The sealed interface makes every consumer
  handle every kind.)
- How do you test something whose output is large? (Golden files, plus properties that must
  always hold: a schema compared with itself is empty; reversing the inputs reverses each change.)
- How do you know the generated SQL is valid? (It is loaded into a real PostgreSQL in the
  round-trip check, and the result is dumped and compared.)

## Phase 3: the risk engine

**What it is.** `RiskAnalyzer.analyze(diff)` runs 38 rules over a diff and returns a
`RiskReport`: a list of findings and a score from 0 to 100. Each finding names the rule, the
change, what happens, the lock the direct statement takes, and the safer way.

```
~ column app.posts.views: type integer -> bigint
  HIGH     SG-R201  Column type change rewrites the table  [table rewrite, score 60]
      Changing app.posts.views from integer to bigint rewrites the whole table while reads and writes wait.
      Direct route: ALTER COLUMN ... TYPE takes ACCESS EXCLUSIVE on app.posts (blocks reads and writes), held while the whole table is written again
```

**How the lock facts were obtained.** Not from memory and not from blog posts. A script runs
each of 51 kinds of DDL inside `BEGIN ... ROLLBACK` on a real PostgreSQL 16 and reads three
things before the rollback: the lock (`pg_locks`), whether the table's file changed
(`pg_relation_filenode`, a rewrite), and whether the table's scan counter went up
(`pg_stat_xact_user_tables`, a full read). The results are a fixture file. The Java enum
`Operation` holds the same facts, and a test fails if the two differ.

**Likely questions**

- *Why measure instead of reading the documentation?* The documentation gives the lock per
  statement but not every case of rewrite or scan, and what people remember is often wrong.
  Measuring found three mistakes of mine before they shipped: `NOT VALID` inside `CREATE
  TABLE` is silently ignored (my first fixture "validated" a constraint that was already
  valid, and showed no scan); `txid_current()` is stable, not volatile; and `VALIDATE
  CONSTRAINT` on a foreign key also locks the referenced table (ROW SHARE).
- *How do you detect that a statement read the whole table?* The per-transaction statistics
  view counts sequential scans as they start. Counter before, statement, counter after, all in
  one transaction.
- *How did you measure `CREATE INDEX CONCURRENTLY`, which cannot run in a transaction?* Three
  sessions: one holds a lock that makes it wait, one runs it, one reads `pg_locks` for the
  waiting session.
- *Which design pattern are the rules?* Strategy: one class per rule behind one abstract
  `Rule`, collected in a registry. `ChangeRule<C>` is a small Template Method on top: it does
  the looping and filtering, the subclass only judges one change.
- *Why does the same change sometimes produce no finding?* Context. Rules get both schemas.
  A foreign key on a table created in the same migration checks zero rows, so it is quiet; on
  an existing table it is HIGH. `SET NOT NULL` is quiet when the old schema already has a valid
  `CHECK (col IS NOT NULL)`, because PostgreSQL then skips the scan (measured).
- *How is the score computed?* Noisy-OR: `100 x (1 - (1 - s1/100)(1 - s2/100)...)`. Each
  finding is treated as an independent chance of trouble. Two MEDIUM findings (30 each) give
  51, not 60 and not 30. It never passes 100 and needs no cap.
- *Why not just take the worst finding?* Then ten HIGH findings would look the same as one.
  *Why not a sum?* It has no upper bound, and a cap throws information away.
- *How do you keep 38 rules honest?* Every rule has at least one schema pair that must fire it
  and one that must not, and a test fails if a rule lacks either. The rule documentation is
  generated from the classes and checked by a test.
- *What can the engine not know?* Table sizes (a rewrite of ten rows scores like one of a
  billion), the application's queries, and whether a function it has never seen is volatile.
  The last one is reported at half weight instead of being guessed.
- *What does ACCESS EXCLUSIVE "held briefly" still risk?* The lock queue. The statement waits
  behind any running query, and every new query waits behind the statement. That is rule
  `SG-R111`: set `lock_timeout` and retry.

## Phase 4.1: the migration planner

**What it is.** `Planner.plan(before, after, options)` writes the SQL that turns one schema
into the other, on one of two routes. Direct: one plain statement per change. Safe: the same
result without long blocking locks.

```sql
-- direct                                   -- safe
ALTER TABLE posts                           ALTER TABLE posts ADD CONSTRAINT posts_body_not_null
  ALTER COLUMN body SET NOT NULL;             CHECK (body IS NOT NULL) NOT VALID;
                                            ALTER TABLE posts VALIDATE CONSTRAINT posts_body_not_null;
                                            ALTER TABLE posts ALTER COLUMN body SET NOT NULL;
                                            ALTER TABLE posts DROP CONSTRAINT posts_body_not_null;
```

**How it works.** Each change becomes one or more statements, each put into a stage. The
stages run in a fixed order that satisfies every dependency (D-044). On the safe route a
blocking statement is replaced by its non-blocking recipe, but only for tables that can have
rows (D-046).

**Likely questions**

- *How do you know the generated SQL is correct?* It is run. For 132 pairs of schemas and both
  routes, a real PostgreSQL 16 runs the old schema and then the plan; its `pg_dump` must
  describe the same schema as the dump of the new schema. That is 264 plans on every CI run.
- *Did that find anything?* Yes, on the first run. (1) A sequence owned by a column of a
  dropped table disappears with the table, but the new schema still used it: the plan now
  detaches it first with `OWNED BY NONE`. (2) An identity column owns a hidden sequence named
  `t_id_seq`; creating a sequence with that name before `DROP IDENTITY` fails, so identities
  are dropped early. Neither would show up by reading the SQL.
- *Why fixed stages and not a topological sort?* The kinds of dependency are few and known.
  A fixed order is deterministic and anyone can read it. The only real cycle is two tables
  with foreign keys to each other, and PostgreSQL drops those in one statement.
- *Why can't the whole plan run in one transaction?* `CREATE INDEX CONCURRENTLY` is refused
  inside a transaction block, and a new enum value cannot be used before it is committed.
  Each step says whether it is transactional.
- *What if a concurrent index build fails half way?* It leaves an invalid index behind. The
  script stops (`ON_ERROR_STOP`); the index has to be dropped before a second try. This is
  the price of not blocking writes, and the rule text says so.
- *What does the safe route not solve?* A type change that rewrites the table, a dropped
  column or table. Those steps stay, marked `CAUTION` with the rule id, because pretending
  otherwise would be a lie (D-048).
- *How are renames handled?* The diff only offers candidates. A person confirms one; the
  planner then runs the `RENAME` through its own catalog builder to get the schema after the
  rename, and plans the rest from there (D-047).
- *What is still unproven?* The oracle uses empty tables, so it proves the schema, not that
  rows survive. A test with rows is on the list.

## Phase 4.2: the migration linter

**What it is.** `MigrationLinter.lint(schema, migrationSql)` judges a script you wrote, line by
line, against the schema it will run on. `CREATE INDEX` gets a HIGH finding;
`CREATE INDEX CONCURRENTLY` gets none. Output carries line numbers, ready for a GitHub Action.

**Likely questions**

- *How is that different from the risk report?* The report only sees two schemas and must
  assume the plain statement. The linter sees the statements, so the exact variant decides.
- *How does it reuse the 38 rules?* Each statement is applied to the catalog, the one-step
  diff goes through the same rules, and a lock finding survives only if the statement is the
  kind that takes that lock (D-050).
- *How do you know linter, rules and planner agree?* Tests bind them: lint the direct plan and
  you must get what the rules found in the diff; lint the safe plan and none of the risks the
  safe route removes may appear (D-054). The first run found a real inconsistency: the safe
  NOT NULL route drops a helper CHECK, which the linter reported as a lost constraint. Fixed by
  judging "what the application meets" findings at the end of the script (D-052).
- *What does it refuse to guess?* Statements it cannot model (functions, views, DO blocks) are
  listed as "not analysed", never silently passed.

## Phase 5: the REST API

**What it is.** A Spring Boot 4 service over the engine: `POST /api/v1/diff`, `/report`,
`/plan`, `/lint` and `GET /api/v1/rules`, documented by OpenAPI at `/swagger-ui.html`.
Stateless: nothing is stored, nothing of the input is logged.

**Likely questions**

- *Why a separate module?* The engine has no dependencies and must stay that way (D-057). The
  API is a thin layer: read inputs, call the engine, map to DTOs.
- *Why not return the engine's records as JSON?* Every internal rename would then break
  clients (D-058). One mapper class is the price.
- *How are errors reported?* RFC 9457 problem documents with a stable `code`. A schema that
  does not parse is a 422 listing each diagnostic's input, line and column, so a UI can
  underline it. A 500 never includes the exception message (D-061).
- *What stops one request from hogging the service?* A body limit checked before parsing,
  including chunked bodies (a filter reads at most the limit), plus limits on input length and
  on statements per script (D-060). Linting cost is schema size times statements, so both are
  bounded.
- *How do you know the Docker image works without Docker locally?* CI builds it, starts it,
  waits for health, calls `/report`, and checks it runs as the non-root user.
- *What was new in Spring Boot 4?* Jackson 3 (`tools.jackson` packages, `JsonNode.asString()`),
  modular test starters (`spring-boot-starter-webmvc-test`; `@AutoConfigureMockMvc` now in
  `org.springframework.boot.webmvc.test.autoconfigure`), RFC 9110 status names
  (`UNPROCESSABLE_CONTENT`, `CONTENT_TOO_LARGE`), and `MockMvcTester` for AssertJ-style tests.
- *A bug you found?* Declaring the 400/413/422 responses at class level made springdoc drop
  every 200 response schema from the OpenAPI document. A test now checks each endpoint's
  response schema is present.

## Phase 5.2: saved analyses (permalinks)

**What it is.** `POST /api/v1/analyses/report` (or diff, plan, lint) runs the analysis, stores
request and result, and returns an id; `GET /api/v1/analyses/{id}` shows it again exactly as
computed; `DELETE` needs the token returned at save time. Kept for 30 days.

**Likely questions**

- *Why can the service run without a database?* Persistence is wired by hand and only when a
  URL is set (D-064). Nothing connects at startup; the schema is migrated on first use (D-065).
  A database that is asleep or down turns the permalink endpoints into 503s and nothing else.
  A test points the service at a closed port and checks exactly that.
- *How do you keep a shared link private enough?* 128 random bits per id, so ids cannot be
  guessed or enumerated; malformed ids are rejected without a query. Deleting needs a separate
  256-bit token, stored only as a hash (D-067). The docs say plainly that saving stores the SQL.
- *Why `json` and not `jsonb`?* The content is never queried, and `json` keeps the exact text,
  so the permalink returns byte for byte what was computed, with the engine version (D-066).
- *Why Spring's `JdbcClient` and not JPA?* One table, four queries. An ORM would add a mapping
  layer and startup cost for nothing.
- *How do you stop storage from filling up?* Limits per analysis and in total, a 507 beyond,
  and an hourly retention job; expired rows are filtered out of every query anyway (D-068).
- *Bugs the tests found?* Three, all of the kind that only shows up at runtime: no
  `-parameters` flag, so `@PathVariable` failed with a 500; the JVM's `Asia/Calcutta` zone,
  which PostgreSQL refused at login; and springdoc describing raw JSON fields as strings
  (OpenAPI 3.1 needs `types`, not `type`) (D-070).
- *What does "dogfooding" mean here?* SchemaGuard lints its own Flyway migrations in a test
  (D-071). The first migration passes with no findings.

## Phase 5.3: first deploy

**What it is.** The Docker image runs on Render's free tier (Singapore), saved analyses live in
Neon PostgreSQL, and a manual workflow checks the live service end to end.

**Likely questions**

- *How do you know the deployed service works, not just the image?* CI starts the image on a
  runner, which proves the code. `live-smoke.yml` calls the real URL: every endpoint, a refused
  schema, save/open/delete with a wrong token first, and a CORS preflight (D-072). The first run
  confirmed the Neon secret and the CORS setting, which no test in the repository can see.
- *What stops a broken commit from going live?* Render waits for the GitHub checks
  (`autoDeployTrigger: checksPass`). Before that, a commit with failing tests would still have
  deployed, because the image build skips the tests to stay fast (D-073).
- *Why is the database secret not in `render.yaml`?* The file is public. The connection string
  is set once in the Render dashboard; the code never logs it with its password (D-069).
- *What does the free tier cost you?* The service sleeps after 15 idle minutes and the first
  call takes about a minute. Health checks never touch the database, so they do not keep Neon
  awake (D-065).

## Phase 6.1: the web app

**What it is.** A React app in `web/` with two Monaco editors. "Compare two schemas" calls diff,
report and plan in parallel and shows Risks, Changes and Plan; "Check a migration" lints a script
statement by statement. Every lock is drawn on PostgreSQL's eight-step lock scale.

**Likely questions**

- *How does the UI stay in step with the API?* The API's OpenAPI document is the contract.
  `OpenApiContractTest` fails the Java build if `web/openapi.json` differs from it, and CI fails if
  the TypeScript types generated from that file differ from the committed ones. A renamed field
  breaks the build, not production (D-074).
- *Why mark nullable fields by hand?* Jackson writes every field, so "optional" in the document
  was wrong; the truth is "always present, sometimes null". `@Nullable` on 13 record components,
  checked by a test, makes the generated types exact, so the UI handles `null` only where it occurs.
- *Monaco is huge; how is the page still fast?* It is a separate 2.7 MB chunk loaded after the
  first render with `React.lazy`; a plain text area takes input meanwhile. The shell is 78 kB
  gzipped (D-075).
- *How do you test a UI that needs a server?* Real responses were recorded from a local API for
  the example schemas and are served by a fake `fetch`. Tests click through the app as a person
  would (Testing Library) and check what is sent, for example the confirmed rename in the `/plan`
  body. A Playwright test against a real server comes in 6.4.
- *What happens when the free server is asleep?* The page pings it on load, and a request pending
  for 3 seconds explains the cold start instead of spinning silently (D-077).

## Phase 6.2: share links and export

**What it is.** "Share" saves an analysis and gives a link `/a/{id}` anyone can open until it
expires; the sharer can delete it. Results download as Markdown or JSON.

**Likely questions**

- *How does the sharer delete a link without an account?* Saving returns a random 256-bit token;
  the server keeps only its SHA-256 (D-067). The browser keeps the token in localStorage, and
  `DELETE` sends it in `X-Delete-Token`. Lose the browser data and the link simply expires.
- *Why store only the report, not the plan and diff too?* They are deterministic and cheap to
  recompute from the saved schemas; "Open in the editor" does exactly that (D-078).
- *How did you handle a confirmed rename making the risk report wrong?* Instead of teaching the
  report about renames, the page lints the generated plan, which already judges `RENAME COLUMN`
  and sees no data loss (D-079). Same engine, no new API.
- *How does a client-side route like `/a/{id}` work on static hosting?* `vercel.json` rewrites
  every path that is not a file to `index.html`; react-router reads the address in the browser.

## Phase 6.3: example gallery and ER diagram

**What it is.** A gallery of seven real schema changes, each one click away, and a Diagram tab
that draws both schemas as one ER diagram with every table, column and foreign key marked.

**Likely questions**

- *Where do the marks come from?* The engine merges both catalogs and takes each mark from the
  diff's significant changes (`ErModel`, D-081). So the diagram cannot disagree with the change
  list: `int4` respelled as `integer` is unchanged in both.
- *Why dagre and not a diagram library?* Layout is the hard part (layered placement, crossing
  reduction); drawing is easy and is where the meaning lives. dagre gives positions, the SVG is
  ours: colours plus words, edges from the exact columns (D-082).
- *How did you make a diagram accessible?* Every mark has a word or symbol as well as a colour;
  the SVG has a summary as its name; it can be zoomed and moved from the keyboard; and the Changes
  tab is the full text alternative.
- *A bug you found by looking?* A real browser showed React's duplicate-key warning when one rule
  gave the same reason twice (three dropped columns). Keys now include the position. Also, a
  second analysis kept the first diagram's zoom; state now resets when the model changes.
