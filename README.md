# SchemaGuard

[![CI](https://github.com/Hanumanth-Raghavendra/schemaguard/actions/workflows/ci.yml/badge.svg)](https://github.com/Hanumanth-Raghavendra/schemaguard/actions/workflows/ci.yml)

A PostgreSQL migration-safety platform. Give it two versions of a schema and it tells you
what changed, what that change will lock or break in production, and how to ship it safely.

> **Status: live. Web app: https://schemaguard-v1.vercel.app (pick an example, see the risks, the plan
> and the diagram, share a link; log in to keep schema versions in projects and watch their risk, or save them from CI with an API key). API: [Swagger UI](https://schemaguard-api-c00y.onrender.com/swagger-ui.html).**
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
- About 1,900 tests; the engine has no runtime dependencies.

## Planned shape

| Module | Role | Status |
| --- | --- | --- |
| `engine` | Lexer, parser, schema model, diff, rules, planner, linter, ER model. Pure Java, no dependencies | Done |
| `api` | Spring Boot REST service: analyses, share links, accounts, projects, API keys; OpenAPI docs and a Docker image | Live |
| `web` | React + TypeScript app: editors, risks, plan, diagram, examples, sharing, accounts, projects, API keys | Live |
| `cli` | The engine on the command line, offline, as one runnable jar | Done |
| `action` | GitHub Action that reviews migrations on pull requests | Done |

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
java -jar api/target/schemaguard-api-0.6.0-SNAPSHOT.jar   # then open http://localhost:8080/swagger-ui.html
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
| `POST /api/v1/auth/signup`, `/login` | Create an account or log in: an access token in the answer, a refresh cookie beside it |
| `POST /api/v1/auth/refresh`, `/logout` | Renew the session with the refresh cookie (it rotates), or end it |
| `GET /api/v1/account`, `DELETE /api/v1/account` | The logged-in account (`Authorization: Bearer`); delete it with its password |
| `GET`, `POST /api/v1/projects`; `GET`, `PATCH`, `DELETE /api/v1/projects/{id}` | The account's projects: list, create, open with its versions, rename, delete |
| `POST /api/v1/projects/{id}/versions`; `GET`, `DELETE .../versions/{number}` | Save a schema as the next version (the risk of the change is kept with it), open one, delete the latest |
| `GET`, `POST /api/v1/account/keys`; `DELETE .../keys/{id}` | The account's API keys: list (without their values), make one (its value is shown this once), delete one |

**API keys** are for scripts and CI. Make one on the account page (or with `POST
/api/v1/account/keys` and a login), keep it in a secret, and send it like an access token. A key
may list and read the account's projects and save versions; everything else needs a login.

```bash
export SCHEMAGUARD_KEY=sg_...   # from the account page
curl -H "Authorization: Bearer $SCHEMAGUARD_KEY" https://schemaguard-v1.vercel.app/api/v1/projects
curl -X POST "https://schemaguard-v1.vercel.app/api/v1/projects/$PROJECT/versions" \
  -H "Authorization: Bearer $SCHEMAGUARD_KEY" -H 'Content-Type: application/json' \
  --data "$(jq -n --rawfile sql schema.sql '{sql: $sql, label: "from CI"}')"
```

A schema identical to the latest version answers 409, so saving on every push keeps only real
changes.

Saving, accounts, projects and API keys need PostgreSQL: set `SCHEMAGUARD_PERSISTENCE_URL` to a JDBC URL or a connection
string such as `postgresql://user:password@host/db?sslmode=require`. Without it everything else
works and those endpoints answer 503. Set `SCHEMAGUARD_AUTH_JWT_SECRET` (32 characters or more) to
keep access tokens valid across restarts. SchemaGuard lints its own database migrations in its tests.

## Use the CLI

The same engine on the command line. Every analysis runs offline; only `save` uses the network.
Requires Java 21 or newer.

```bash
./mvnw -B -ntp -pl cli -am package -DskipTests          # Windows: mvnw.cmd -B -ntp -pl cli -am package -DskipTests
java -jar cli/target/schemaguard-cli-0.6.0-SNAPSHOT.jar --help
```

```bash
schemaguard() { java -jar cli/target/schemaguard-cli-0.6.0-SNAPSHOT.jar "$@"; }

schemaguard diff before.sql after.sql                    # every change, with likely renames
schemaguard report before.sql after.sql --fail-above medium
schemaguard plan before.sql after.sql --route safe       # the migration, as a psql script
schemaguard lint schema.sql migration.sql --format markdown > check.md
schemaguard lint --migrations src/main/resources/db/migration V7__orders.sql --fail-above high
pg_dump --schema-only mydb | schemaguard report - schema.sql   # the database now, against the schema wanted
```

| Option | Does |
| --- | --- |
| `--format text\|markdown\|json` | Text is the engine's report; Markdown is the web app's download layout, ready for a pull-request comment; JSON is the API's response body, field for field |
| `--fail-above <limit>` | Exit with 1 when the risk is above a score (0 to 100) or a level (`none`, `low`, `medium`, `high`). For `lint`, a statement PostgreSQL would refuse fails too |
| `lint --migrations <folder> [files...]` | Each file against the schema the files before it build. Flyway's `V<version>__` files run in version order; other names in name order |

Exit codes: 0 done, 1 risk above the limit, 2 bad usage or input, 3 the API refused or could not
be reached, 4 a bug. Results go to standard output; warnings and errors go to standard error as
`file:line:column: severity: message [code]`.

`save` keeps a schema as a project's next version on the site, as CI might after each release. It
reads an [API key](#run-the-api) from `SCHEMAGUARD_KEY` and never takes one as an option:

```bash
SCHEMAGUARD_KEY=sg_... schemaguard save --project 3f9c2a1e-8b7d-4c6e-9f10-2a3b4c5d6e7f --label "Release 2.4" schema.sql
```

A schema identical to the latest version prints "Nothing saved" and exits 0. `SCHEMAGUARD_API`
points it at another SchemaGuard site; `SCHEMAGUARD_TIMEOUT` changes the 120-second wait.

## Check pull requests with the GitHub Action

The [action](action/README.md) runs the CLI on the migrations a pull request adds, comments with
the report (one comment, updated on every push), annotates lines PostgreSQL would refuse, and
fails the check above a limit:

```yaml
- uses: actions/checkout@v7
- uses: Hanumanth-Raghavendra/schemaguard/action@v0.6.0   # releases carry the CLI from v0.6.0
  with:
    migrations: src/main/resources/db/migration
    fail-above: medium
```

The workflow needs `pull-requests: write` for the comment. Publishing a GitHub release attaches the
CLI jar the action downloads (`.github/workflows/release.yml`).

## Run the web app

Needs Node 22 (with nvm: `nvm install 22`, then `nvm use 22`) and the API running on
`http://localhost:8080` (see above). From the repository root:

```bash
cd web
npm ci
npm run dev
```

Open http://localhost:5173. The app calls `/api` on its own address and Vite forwards it to the API
(as Vercel does in production). To use the live API instead of a local one, start it with
`SCHEMAGUARD_API=https://schemaguard-api-c00y.onrender.com npm run dev` (PowerShell:
`$env:SCHEMAGUARD_API="https://schemaguard-api-c00y.onrender.com"; npm run dev`). Checks: `npm run lint`, `npm run typecheck`, `npm test`, `npm run build`.
The TypeScript types come from `web/openapi.json`, which the API build keeps equal to the API's
OpenAPI document; after an API change run `npm run gen:api`.

## Documentation

- [PROGRESS.md](PROGRESS.md): current state and next step
- [docs/DECISIONS.md](docs/DECISIONS.md): architecture decisions and their reasons
- [docs/RULES.md](docs/RULES.md): every rule, why it matters and the safer alternative
- [docs/INTERVIEW.md](docs/INTERVIEW.md): how each part works and why

## License

[MIT](LICENSE) © 2026 Hanumanth Raghavendra
