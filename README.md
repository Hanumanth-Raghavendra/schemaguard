# SchemaGuard

[![CI](https://github.com/Hanumanth-Raghavendra/schemaguard/actions/workflows/ci.yml/badge.svg)](https://github.com/Hanumanth-Raghavendra/schemaguard/actions/workflows/ci.yml)

A PostgreSQL migration-safety platform. Give it two versions of a schema and it tells you
what changed, what that change will lock or break in production, and how to ship it safely.

> **Status: live. Web app: https://schemaguard-v1.vercel.app (pick an example, see the risks, the plan
> and the diagram, share a link). API: [Swagger UI](https://schemaguard-api-c00y.onrender.com/swagger-ui.html).**
> Free hosting: the first analysis after 15 idle minutes takes about a minute while the API wakes up.
> See [PROGRESS.md](PROGRESS.md) for the current state and roadmap.

## Why

A single `ALTER TABLE` can take a lock that blocks every write to a busy table. Most teams find
out in production. SchemaGuard checks the migration before it runs:

1. **Diff**: a structured comparison of two schemas, including likely renames.
2. **Risk**: every change is judged by 40 rules for locks, table rewrites, data loss, failures
   and application compatibility, then scored from 0 to 100 ([rule reference](docs/RULES.md)).
3. **Plan**: the migration written for you, either the plain way or a safe way that avoids long
   locks (`CREATE INDEX CONCURRENTLY`, `NOT VALID` + `VALIDATE`, `lock_timeout`, ...).
4. **Lint**: a migration script you wrote yourself, checked statement by statement, with line
   numbers.

## What it looks like

A hand-written migration, linted against the schema it will run on:

```text
line 17: ALTER TABLE app.posts ALTER COLUMN body SET NOT NULL
  HIGH     SG-R106  SET NOT NULL scans the table under a full lock  [locking, score 60]
      Making app.posts.body NOT NULL reads every row to look for NULLs while reads and writes wait.
      It fails if a NULL is found.
      Lock: ALTER COLUMN ... SET NOT NULL takes ACCESS EXCLUSIVE on app.posts (blocks reads and writes),
            held while every row is read
      Safer: Add CHECK (column IS NOT NULL) NOT VALID, run VALIDATE CONSTRAINT, then SET NOT NULL ...
```

And the same change on the safe route of the generated plan:

```sql
ALTER TABLE app.posts ADD CONSTRAINT posts_body_not_null CHECK (body IS NOT NULL) NOT VALID;
ALTER TABLE app.posts VALIDATE CONSTRAINT posts_body_not_null;   -- reads and writes continue
ALTER TABLE app.posts ALTER COLUMN body SET NOT NULL;            -- no scan: the check is the proof
ALTER TABLE app.posts DROP CONSTRAINT posts_body_not_null;
```

## How it is kept honest

- **Lock facts are measured, not remembered.** `scripts/measure-locks.sh` runs 51 kinds of DDL on
  a real PostgreSQL 16 and records the lock, rewrite and table scan of each. The engine's
  knowledge is tested against those measurements.
- **Every generated plan is run.** In CI, a PostgreSQL 16 container applies 276 plans; the
  `pg_dump` afterwards must match the target schema.
- **Planner, rules and linter are tested against each other**, and the engine is fuzzed with
  broken SQL: any input gives an answer, never a crash.
- About 1,700 tests; the engine has no runtime dependencies.

## Planned shape

| Module | Role | Status |
| --- | --- | --- |
| `engine` | Lexer, parser, schema model, diff, rules, planner, linter. Pure Java, no dependencies | Done for v0.1 |
| `api` | Spring Boot REST service with OpenAPI docs and a Docker image | Done for v0.1 |
| `cli` | Command-line tool built on the engine | Planned |
| `web` | React + TypeScript front end | Planned |
| `action` | GitHub Action that reviews migrations on pull requests | Planned |

## Build

Requires JDK 21 or newer. Maven is downloaded by the wrapper.

```bash
./mvnw -B -ntp verify        # Linux, macOS, Git Bash
mvnw.cmd -B -ntp verify      # Windows Command Prompt or PowerShell
```

This compiles the code, runs the tests and enforces the coverage gates.

## Run the API

```bash
./mvnw -B -ntp package -DskipTests
java -jar api/target/schemaguard-api-0.5.0.jar   # then open http://localhost:8080/swagger-ui.html
```

Or with Docker: `docker build -t schemaguard-api .` and `docker run -p 8080:8080 schemaguard-api`.

The same image runs at https://schemaguard-api-c00y.onrender.com (Render, Singapore; saved
analyses in Neon PostgreSQL). Replace `http://localhost:8080` with that address to call it.
The [live smoke test](.github/workflows/live-smoke.yml) checks the deployed service end to end.

```bash
curl -X POST http://localhost:8080/api/v1/report -H 'Content-Type: application/json' \
  -d '{"before":"CREATE TABLE t (a int);","after":"CREATE TABLE t (a int); CREATE INDEX t_a ON t (a);"}'
```

| Endpoint | Does |
| --- | --- |
| `POST /api/v1/diff` | Every difference between two schemas, with rename candidates |
| `POST /api/v1/report` | Risk of each change on the direct route, scored 0 to 100 |
| `POST /api/v1/model` | Both schemas as one ER model, each table, column and foreign key marked added, removed, changed or unchanged |
| `POST /api/v1/plan` | The migration, direct or safe route, with confirmed renames |
| `POST /api/v1/lint` | A hand-written migration, judged statement by statement |
| `GET /api/v1/rules` | Every rule, why it matters and the safer alternative |
| `POST /api/v1/analyses/{diff,report,plan,lint}` | Run an analysis and save it for 30 days; returns a shareable id and a delete token |
| `GET /api/v1/analyses/{id}` | Open a saved analysis exactly as it was computed |
| `DELETE /api/v1/analyses/{id}` | Delete it early (header `X-Delete-Token`) |

Saving needs PostgreSQL: set `SCHEMAGUARD_PERSISTENCE_URL` to a JDBC URL or a connection string
such as `postgresql://user:password@host/db?sslmode=require`. Without it everything else works
and the save endpoints answer 503. SchemaGuard lints its own database migrations in its tests.

## Run the web app

Needs Node 22 (with nvm: `nvm install 22`, then `nvm use 22`) and the API running on
`http://localhost:8080` (see above). From the repository root:

```bash
cd web
npm ci
npm run dev
```

Open http://localhost:5173. Checks: `npm run lint`, `npm run typecheck`, `npm test`, `npm run build`.
The TypeScript types come from `web/openapi.json`, which the API build keeps equal to the API's
OpenAPI document; after an API change run `npm run gen:api`.

## Documentation

- [PROGRESS.md](PROGRESS.md): current state and next step
- [docs/DECISIONS.md](docs/DECISIONS.md): architecture decisions and their reasons
- [docs/RULES.md](docs/RULES.md): every rule, why it matters and the safer alternative
- [docs/INTERVIEW.md](docs/INTERVIEW.md): how each part works and why

## License

[MIT](LICENSE) © 2026 Hanumanth Raghavendra
