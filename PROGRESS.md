# SchemaGuard: progress

> **To resume:** read this file, then start at "Next step". Do not re-plan; the plan is fixed below.

- **Current phase:** 9 (hardening and launch), in progress: 9.1 (rate limits) done and live (2026-11-26); 9.2 (migrations over a direct connection) done and live (2026-11-27); 9.3 (caching) next. Phase 8 is done (2026-11-26): the CLI, the GitHub Action with its release workflow, and SchemaGuard checking its own migrations on every pull request (seen working on pull request #4). Phase 7 is done (2026-11-24): accounts, projects and schema versions, risk trend and saved reports, API keys, all live. Phase 6 is done: web app live at https://schemaguard-v1.vercel.app (renamed from schemaguard-nu on 2026-11-22), released as `v0.5.0` (tagged by the owner on 2026-11-22). Phases 1 to 5 are complete; v0.1 is live at https://schemaguard-api-c00y.onrender.com (Render free tier, Singapore; saved analyses in Neon).
- **Last updated:** 2026-11-27
- **Build command (repo root):** `./mvnw -B -ntp verify` (Windows: `mvnw.cmd -B -ntp verify`)
- **State:** build green; engine 1,714 tests (276 plans run on a real PostgreSQL 16 in CI), API 169 tests (permalinks, accounts, projects, API keys and migrations tested on real PostgreSQL; rate limits on a real server); CLI 82 tests (its JSON checked against the API contract); GitHub Action 34 script tests plus two real runs in CI, and it checks this repository's own pull requests; web 95 tests (Vitest) and 4 end-to-end tests (Playwright); line coverage engine 99.3% (gate 85%), API 97.0% (gate 80%), CLI 96.4% (gate 85%); CI runs the CLI jar and builds the Docker image, starts it and calls it; the live smoke test passes against the deployed service
- **Size:** 494 files in 82 folders; 20,607 lines of main Java, 11,452 lines of test Java, 7,002 lines of TypeScript (plus 2,536 generated), 599 lines of Bash (the action and its tests)

## Next step

**Live service.** Web app https://schemaguard-v1.vercel.app (Vercel, redeploys on every push to `main`).
API https://schemaguard-api-c00y.onrender.com (Swagger UI at `/swagger-ui.html`).
Render builds the `Dockerfile` from `main` after the CI checks pass, and only when a push changes
what goes into the image (`render.yaml`: `autoDeployTrigger: checksPass`, `buildFilter`). Neon's
connection string is the dashboard secret `SCHEMAGUARD_PERSISTENCE_URL` (never in the repository).
After any deploy, run the live smoke test (manual workflow `live-smoke.yml`; checks health, docs,
all analyses, save/open/delete, the visitor's rate limit through Vercel, CORS). From the Claude container (`gh workflow run` is blocked
there, the REST call works):
`gh api -X POST repos/Hanumanth-Raghavendra/schemaguard/actions/workflows/live-smoke.yml/dispatches -f ref=main -f 'inputs[persistence]=required'`
(or GitHub > Actions > Live smoke test > Run workflow). It prints the jar's build time, which
changes only when the jar is rebuilt (Docker layer cache). Both deploy rules were verified on
2026-11-18: a `pom.xml` change deployed 4 s after CI went green; a docs-only push deployed
nothing. Render reports each deploy to GitHub (repository > Deployments). The live smoke test
also runs a throwaway account end to end and checks the refresh cookie survives the Vercel rewrite
(first passed on 2026-11-22 for 7.1).

**Phase 6: web UI (v0.5), live.** Requirements (Blueprint, tier 2 "Web app"): analyse without login
in two code editors, visual diff, risk report, shareable permalink; interactive ER diagram with
changed tables and columns highlighted; example gallery of real-world scenarios, so a recruiter
sees results in one click; export of the report as Markdown or JSON. All four slices are done (see Done); released as `v0.5.0`. Live at https://schemaguard-v1.vercel.app. The
app lives in `web/` (Vite 8, React 19, TypeScript 5.9, Tailwind 4, Monaco; see D-075).

**Phase 7: accounts, projects, history, trends, API keys.** The version is `0.6.0-SNAPSHOT` since 2026-11-22 (D-087).

**Phase 7 scope.** Blueprint tier 3: sign-up and login
with JWT access and refresh tokens; projects with schema versions on a timeline, a risk trend chart
and saved reports; per-user API keys for the CLI and CI. Done when a user can save schema versions
and see the risk trend. Accounts (7.1) are settled in D-091 to D-096: Spring Security 7.1 resource
server, RFC 9068 access tokens (HS256, 15 min, memory only in the browser), opaque rotating refresh
tokens in an httpOnly SameSite=Strict cookie with reuse detection, Argon2id, per-account backoff.
New endpoints need a token by adding their path to `SecurityConfig` with the authority it needs
(`SESSION` for a login, `KEY` as well where an API key may call it) and
`@SecurityRequirement(name = SecurityConfig.BEARER)` (plus `API_KEY` where keys work); the web app
calls them through `useAuth().withToken(...)`, which renews an expired token once.

- **7.2 projects and schema versions: done** (D-097 to D-100). `/api/v1/projects` (list, create,
  get, rename, delete; save a version, open one, delete the latest), Flyway V3 `project` and
  `schema_version`, pages `/projects`, `/projects/:id`, `/projects/:id/versions/:n`, and "Save the
  new schema to a project" in the analyser. Each version stores the risk of the change from the one
  before (`risk_score`, `risk_level`, `risk_reasons`, `changes`, `findings`), which is what 7.3 charts.
- **7.3 risk trend chart and saved reports: done** (D-101, D-102). Phase 7's goal ("a user can save
  schema versions and see the risk trend") is met and verified live.
- **7.4 API keys: done** (D-103 to D-106). Phase 7 is complete.

**Phase 8: CLI, GitHub Action, dogfooding.** Blueprint tier 4: the CLI is "the same engine as a
runnable jar; works offline"; the GitHub Action "fails the build above a risk threshold and posts
the report on the pull request"; dogfooding means "SchemaGuard's own migrations are checked by
SchemaGuard in its own pipeline". Modules `cli` (picocli, depends on `engine` only) and `action`
(runs the CLI). Done when "the action comments on a test pull request; our own migrations pass our
own check". Check the current picocli release and how to build one runnable jar before starting.

- **8.1 CLI: done** (D-107 to D-110). `cli/target/schemaguard-cli-<version>.jar`, built by
  `./mvnw -B -ntp -pl cli -am package -DskipTests`; usage in the README. CI runs the jar and keeps it
  as the artifact `schemaguard-cli`.
- **8.2 GitHub Action: done** (D-111 to D-113). `action/` (usage in `action/README.md`) and
  `.github/workflows/release.yml`.
- **8.3 Dogfooding: done** (D-114, D-115). Phase 8 is complete.

**Phase 9: hardening and launch.** Blueprint: "rate limits, caching, docs, README with diagrams,
demo video, resume and portfolio updated; version 1.0 tagged". Slices, each tested and live:

- **9.1 Rate limits: done** (D-116, D-117). Package `api/.../ratelimit`; settings
  `schemaguard.rate-limits`; the live smoke test checks the visitor is counted through Vercel.
- **9.2 Migrations over a direct connection: done** (D-118). No question for the owner was
  needed: behind Neon's pooler the direct host is derived; `migration-url` covers other poolers.
- **9.3 Caching.** `Cache-Control` and `ETag` for what never changes once written (saved
  analyses, schema versions, `/api/v1/rules`, the OpenAPI document), checked through Vercel.
- **9.4 Docs.** README rewritten for a first-time reader: what it catches (a real example), a
  Mermaid diagram of the modules and the data flow, another of the deployment (Vercel, Render,
  Neon), quick starts for the site, the CLI and the action, screenshots taken from the live site.
- **9.5 Demo video.** A scripted walk through the live site recorded with Playwright (paste,
  risks, plan, diagram, share, account, project and trend), plus the pull-request comment; a short
  MP4 and a GIF for the README.
- **9.6 Resume and portfolio.** Resume bullets and a portfolio entry (his personal website)
  written from `docs/INTERVIEW.md` and the numbers here, for the owner to place.
- **9.7 Release 1.0.** The Releasing steps below with `1.0.0`; then the action is used as
  `@v1.0.0`. Optional: a small repository holding only the action, for the GitHub Marketplace.

**Releasing** (D-113). 1. Claude commits the release: every version without `-SNAPSHOT` (`pom.xml`
files, `web/package.json` and its lock file), the CHANGELOG section dated. 2. The owner publishes
the release on GitHub: Releases, Draft a new release, tag `v<version>` created on publish from
`main`, title `SchemaGuard <version>`, notes from the CHANGELOG, Publish. 3. The Release workflow
attaches `schemaguard-cli-<version>.jar` and its `.sha256` (about 3 minutes); if the version and
tag differ it fails and says so. 4. Claude moves every version to the next `-SNAPSHOT`.

**Local PostgreSQL.** The Claude build container has PostgreSQL 16 server binaries in
`/usr/lib/postgresql/16/bin`. Run them as the `postgres` user (`runuser -u postgres -- ...`)
with a work directory under `/var/lib/postgresql/`. Start one, then use
`scripts/regenerate-golden.sh` (see `engine/src/test/resources/golden/README.md`).
**Settle every doubt about PostgreSQL behaviour by asking the server, not from memory.** In
phase 1 this caught four wrong assumptions before they shipped. Docker is not available there.
Run the plan oracle locally: start the server on TCP (`-c listen_addresses='127.0.0.1' -p 54329`),
then `SG_PG_ORACLE=1 PGHOST=127.0.0.1 PGPORT=54329 PGUSER=postgres PGBIN=/usr/lib/postgresql/16/bin ./mvnw -B -ntp verify`.
Without `SG_PG_ORACLE` those tests are skipped; CI always runs them (postgres:16 service).
Lock facts: `scripts/measure-locks.sh operations|types|conflicts|functions` (usage in its header).

## Done

- [x] **Phase 0** Blueprint (2026-11-12)
- [x] **1.1** Scaffold: Maven multi-module build, Maven wrapper, GitHub Actions CI, coverage gate, docs (2026-11-12)
- [x] **1.2** Lexer (2026-11-12)
- [x] **1.3** Schema model: `QualifiedName`, `DataType`, `Column`, sealed `Constraint` family, `Table`, `Expression` (2026-11-12)
- [x] **1.4a** Parser: statement splitter, token cursor, type parser, expression renderer, full `CREATE TABLE` (2026-11-12)
- [x] **1.4b** Parser: `CREATE TYPE ... AS ENUM`, `ALTER TYPE` add/rename value, `CREATE SEQUENCE`, `ALTER SEQUENCE ... OWNED BY`, `CREATE INDEX`, `ALTER TABLE` (17 actions), `DROP`; parser split into one class per statement family (2026-11-13)
- [x] **1.6 (part)** First golden fixture: a real `pg_dump` from PostgreSQL 16.15 parses with no errors and only the four expected "unsupported" warnings (view, function, trigger, partition attach) (2026-11-13)
- [x] **1.5** Catalog: `CatalogBuilder` applies statements in order with PostgreSQL's rules (generated names, serial expansion, sequence defaults, renames, cascading drops, atomic statements); 73 error cases, each worded as PostgreSQL 16 words it (2026-11-13)
- [x] **1.6** Oracle tests: for three fixtures, the catalog built from the script equals the catalog built from the real `pg_dump` of that script. **Phase 1 complete** (2026-11-13)
- [x] **2.1** `SqlWriter`: model back to PostgreSQL DDL, and a whole catalog to a pg_dump-ordered script. Parser round trip is exact for every fixture, and PostgreSQL 16 accepts every written script and dumps it back to an identical catalog (2026-11-14)
- [x] **2.2** Diff engine: `SchemaDiff.compare(before, after)`, 34 sealed `Change` kinds, rename detection by definition (constraints, indexes) and scored rename candidates (tables, columns, enum values), cosmetic flag, fixed output order, `ChangeFormatter` (2026-11-14)
- [x] **2.3** Evolution fixture: version 1 schema + migration, dumped before and after by PostgreSQL 16; golden `expected-diff.txt`. **Phase 2 complete** (2026-11-14)
- [x] **3.1** Lock knowledge base, measured: `scripts/measure-locks.sh` runs 51 kinds of DDL on PostgreSQL 16 and records lock, rewrite and scan for each; also 56 type changes, the 8x8 lock conflict table and the volatility of 2,633 built-in functions. `Operation`, `LockLevel`, `TypeChange`, `Volatility` hold the knowledge and tests compare them with the measurements (2026-11-14)
- [x] **3.2** Rules engine: `Rule` / `ChangeRule`, `RuleRegistry`, `RuleContext`, `Finding`, 38 rules in five categories (ids `SG-R101` to `SG-R509`), each with a case that fires and one that stays quiet (2026-11-14)
- [x] **3.3** Scoring and output: `RiskScore` (noisy-OR), `RiskReport`, `ReportFormatter`, golden `expected-report.txt`, generated `docs/RULES.md`. Catalog fix found by measuring: `NOT VALID` inside `CREATE TABLE` is ignored. **Phase 3 complete** (2026-11-14)
- [x] **4.1** Migration planner: `Planner.plan(before, after, options)` writes the direct route and the safe route (concurrent indexes, `NOT VALID` + `VALIDATE`, NOT NULL through a validated CHECK, keys through `USING INDEX`, swap of changed constraints and indexes, `lock_timeout`), confirmed renames, cautions for risks it cannot remove, and a list of changes PostgreSQL has no statement for. `PlanOracleTest` runs 264 plans on a real PostgreSQL 16 and compares `pg_dump` with the wanted schema; CI does the same with a postgres:16 service (2026-11-15)
- [x] **4.2** Parser and catalog read every statement the planner writes (`ALTER INDEX ... RENAME`, `SET GENERATED`, `DROP EXPRESSION`, `SET LOGGED/UNLOGGED`, `INHERIT/NO INHERIT`, `ALTER SEQUENCE` options, `CREATE TABLE ... INHERITS` with merged columns), checked against PostgreSQL by a new `alterations` fixture. `MigrationLinter` judges a hand-written script statement by statement against the schema it runs on: a script that already says `CONCURRENTLY` is not blamed, a table the script created is empty, gaps the script closes itself are not reported; two script-only rules (`SG-R403` CONCURRENTLY in a transaction, `SG-R510` rename in one step). Tests prove linter, rules and planner agree. **Phase 4 complete** (2026-11-15)
- [x] **Audit** before Phase 5: engine fuzzed with 60,000 broken scripts (no crash; a seeded 1,500-case version is now a test); linter made 4x faster (equal tables skipped in the diff, unchanged tables kept as the same objects, guarded by a test); report lines name the real tables (no more "each table it references" for a table that references none, `DROP INDEX` names its table); order-unsafe `withRenames(Set)` removed; unused imports removed; Javadoc clean under `-Xdoclint`; README rewritten with a real example (2026-11-16)
- [x] **5.1** REST API module `api` (Spring Boot 4.1.2, Java 21, Jackson 3, springdoc 3.2.0): `POST /api/v1/diff|report|plan|lint`, `GET /api/v1/rules`; own DTOs (engine types never leave the service); rename candidates from the diff go back to `/plan` as typed JSON; RFC 9457 problem documents (400, 413, 422 with line and column diagnostics, 500 without details); limits (body 3 MB incl. chunked, 1 M characters per input, 2,000 statements per script); CORS from config; Swagger UI; actuator health; virtual threads; gzip. Multi-stage Dockerfile (JRE, non-root, health check) and `render.yaml`; a CI job builds the image, starts it and calls it. Jar smoke test: starts in 3 s, ~235 MB resident, lint of the evolution fixture ~30 ms (2026-11-17)
- [x] **5.2** Saved analyses (permalinks): `POST /api/v1/analyses/{diff,report,plan,lint}` runs the analysis and stores request + result (201, `Location`); `GET /api/v1/analyses/{id}` returns them exactly as computed; `DELETE` with the `X-Delete-Token` returned at save time. Ids are 128 random bits in base 62; only the SHA-256 of the token is stored. 30-day retention (hourly job), 1 MB per analysis, 200 MB in total (507 beyond). PostgreSQL through plain spring-jdbc + HikariCP + Flyway, wired by hand: off without `SCHEMAGUARD_PERSISTENCE_URL` (503), lazy (nothing connects at startup, schema migrated on first use), a down database costs only the permalinks. Accepts Neon's `postgresql://user:pass@host/db` string. SchemaGuard lints its own migrations in a test. Bugs found by the tests: missing `-parameters` (path variables gave 500), JVM zone `Asia/Calcutta` refused by PostgreSQL at login (service now runs in UTC), springdoc describing raw JSON as `string` (now `types = object`) (2026-11-17)
- [x] **5.3** First deploy: Render (Docker, free, Singapore) from the Blueprint, Neon PostgreSQL for saved analyses. Manual workflow `live-smoke.yml` checks the deployed service end to end (health, OpenAPI, rules, diff, report, plan, lint, 422 on a bad schema, save/open/wrong token 403/delete 204/gone 404, CORS preflight); first run green. Render now deploys only after CI passes (the image build skips tests, so a red commit used to go live) and only for paths that go into the image (2026-11-18)
- [x] **6.1** Web app in `web/`: two Monaco editors (bundled, loaded after the page; plain text area meanwhile), "Compare two schemas" (diff, report and plan in parallel; tabs Risks, Changes, Plan) and "Check a migration" (lint, statement by statement). Each lock is drawn on PostgreSQL's eight-step lock scale, coloured by what traffic does. Plan tab: Safe/Direct route and rename confirmation, which re-plan; copy script. API problems are listed with input, line and column and underlined in the editor; a notice explains the free host's cold start, which a ping on page load starts early. The OpenAPI document is now exact (every response field required, 13 nullable ones marked with jspecify `@Nullable`, rename subtypes a clean union) and copied to `web/openapi.json` by `OpenApiContractTest`; TypeScript types are generated from it and CI fails if either drifts. CI job "Web app": lint, type check, test, build. Tests use real API answers recorded from a local server (2026-11-18)
- [x] **6.2** Share and export. "Share" saves the report (compare) or the lint (migration) through `/api/v1/analyses/{kind}` and shows a link `/a/{id}` with its expiry; the delete key is kept in localStorage and expired keys are dropped. `/a/{id}` (react-router 8.5.1) shows the saved result and inputs, "Open in the editor" (loads the inputs and runs the full analysis), downloads, and "Delete link" with an inline confirmation for the sharer; expired, deleted, wrong-key and switched-off cases have plain messages. Downloads: Markdown built in the browser (score, reasons, risks worst first with why and instead, changes, plan SQL; or statement by statement) and JSON (inputs with every response). "Check this plan as a migration" lints the generated plan; the Risks tab points to it once a rename is confirmed. `web/vercel.json` sends every path to `index.html`. 17 new tests on recorded save/open/delete answers; checked in Chromium against a local API with PostgreSQL (2026-11-19)
- [x] **6.3** Example gallery and ER diagram. Engine `diagram.ErModel` merges both catalogs into one model and marks every table, column and foreign key added, removed, changed or unchanged from the diff's significant changes (5 tests); `POST /api/v1/model` serves it (status values are enums in the contract, so TypeScript gets a union). The Diagram tab lays tables out left to right with dagre 3.1.3 (referenced tables first) and draws them in SVG: change colours plus words (new, dropped, changed, +, −, ~, old → new type), a curve from each foreign key's column to the referenced column, drag to pan, Ctrl+wheel, buttons or keys to zoom, a fitted view per analysis; the chunk (19 kB gzipped) loads on first open. The gallery (7 scenarios: a typical release, required column, foreign key, int to bigint, rename, index, split a table; each checked against the API) fills both editors and runs the analysis in one click. Fixed duplicate React keys when the same reason or caution appears twice. Live smoke test now checks `/api/v1/model` (2026-11-20)
- [x] **6.4** End to end and deploy. One Playwright 1.63.0 test (example, risks, changes, diagram, plan with a confirmed rename, share, open in a new page, delete, link gone) runs in the CI job "End to end" against the API jar with PostgreSQL and `vite preview`, and against the live site in the live smoke test's new "browser" job (`E2E_BASE_URL`). Vercel project `schemaguard` (team "blindcoder", root `web/`, production branch `main`) serves https://schemaguard-v1.vercel.app; `web/vercel.json` pins Vite, `npm ci`, `npm run build`, `dist` and the SPA rewrite. `render.yaml` allows that origin plus `http://localhost:5173`; the Blueprint sync redeployed the API. `live-smoke.yml` has inputs `origin` and `web` and fails when the origin is not allowed. First full live run green (2026-11-20)
- [x] **Audit 2** (2026-11-20). Fixed: a failed download of the editor or diagram chunk blanked the whole page (no error boundary); now the plain editor or a message takes its place. Added a Content-Security-Policy and other security headers on Vercel, tested under the policy with the end-to-end test (which now fails on any CSP block and checks Monaco really loaded), and year-long caching for hashed assets. The parser no longer accepts an unquoted reserved word as a constraint name, matching PostgreSQL 16 (checked with psql). Version 0.5.0 everywhere before the tag, `CHANGELOG.md`, social preview tags and image for shared links, clearer page title, monthly grouped Dependabot updates. Stale known issues removed (licence, reserved names)
- [x] **7.0** One origin for the web app and the API (D-090): Vercel rewrites `/api/*` to Render (rewrite caching off); Vite dev and preview forward `/api` too, so the CI end-to-end test runs the same way. The page still pings the API host directly to wake it. CORS keeps the Vercel origin (the rewrite forwards `Origin`) and now allows the local preview origin by default. Live smoke test checks the proxy. Also: version 0.6.0-SNAPSHOT, and `/actuator/info` shows the real build time again (Maven 3.10 stamped 1980-02-01) (2026-11-22)
- [x] **7.1** Accounts (D-091 to D-096). API: `POST /api/v1/auth/signup|login|refresh|logout`, `GET` and `DELETE /api/v1/account`; Spring Security 7.1.2 resource server checking RFC 9068 access tokens (HS256, 15 minutes, key generated by Render); opaque refresh tokens (SHA-256 stored) in an httpOnly, Secure, SameSite=Strict `__Secure-sg-refresh` cookie on `/api/v1/auth`, rotated on every use, reuse after 30 s revokes the session, 30-day idle and 90-day absolute lifetime; Argon2id (Bouncy Castle 1.87, 19 MiB, 2 passes, at most 2 at once); 8 to 256 characters, not among the 10,000 most common, not the email; 5 failures in an hour lock the account for 1 minute, doubling to 15; unknown emails cost the same check; delete needs the password (403). Problem documents for every error, `WWW-Authenticate: Bearer` on 401. Shared `Database` class (lazy Flyway, transactions); Flyway V2 `account` and `refresh_token`, linted by SchemaGuard; the retention job also deletes expired tokens; CORS moved into Spring Security. Web: header links, `/signup`, `/login`, `/account` (details, delete with password); access token in memory, one refresh at a time, renewal on load only for browsers that logged in, logins and logouts followed across tabs. Tests: 60 new API tests (service-level with a moved clock on PostgreSQL: rotation, reuse, two tabs, expiry, 90-day cap, lockout growth, rehash; HTTP-level: cookie attributes, 401/403/409/422/429, foreign origin refused), 13 new Vitest tests, a Playwright test of an account's whole life (sign up, reload, log out, wrong password, log in, delete) in CI and live. Live smoke test runs a throwaway account by curl and checks `Set-Cookie` through the Vercel rewrite (2026-11-22)
- [x] **7.2** Projects and schema versions (D-097 to D-100). API under `/api/v1/projects` (Bearer, owner-only, another account's project is a 404 like a missing one): list, create, get, rename (PATCH), delete; save a version (whole schema; the risk of the change from the latest is stored as score, level, top reasons and counts; identical schema 409; analysis outside the transaction, then an optimistic check of the latest number under a project row lock), open a version with the SQL before it, delete only the latest. Limits 20 projects, 500 versions, 20 MB per account (422), 200 MB for all (507). Flyway V3 `project` (unique `(account_id, lower(name))`) and `schema_version` (primary key `(project_id, number)`, risk columns null exactly for version 1), linted by SchemaGuard; account deletion cascades. Web: projects list and create, project page (rename, delete, timeline, form for the next version starting from the latest), version page (saved risk, SQL, open the change in the analyser, delete the latest), "Save the new schema to a project" in the analyser; `useAuthed` and `RequireAuth`. Tests: 16 new API tests (HTTP on PostgreSQL incl. isolation, 409s, cascade; service level: limits, 8 parallel writers), 17 new Vitest tests, a Playwright test of a project's life (Monaco input, two versions, the analyser, a refused duplicate) in CI and live; the live smoke test saves two versions by curl (2026-11-24)
- [x] **7.3** Risk trend and saved reports (D-101, D-102). Project page: a bar per version (score, level colour, lines where Medium, High and Critical start), a sentence naming the latest and the riskiest change, each bar a link to its version, the latest 60 versions; HTML and CSS, no chart library. Version page: download the change's report as Markdown (names the project and version, says the rules are today's) or JSON, share it as a link (a saved analysis), download any version's SQL; one analysis (report, changes, safe plan) serves both downloads. Also fixed: the None level tag had no border (`border-none` is a Tailwind utility, not the colour). Tests: 6 new Vitest tests (chart order and links, 70 versions cut to 60, no chart for one version, downloads analysed once, share, SQL download); the Playwright project test clicks a bar and downloads the report, in CI and live. Web only: no API change, so Render does not redeploy (2026-11-24)
- [x] **7.4** API keys (D-103 to D-106). API: `GET`/`POST /api/v1/account/keys`, `DELETE /api/v1/account/keys/{id}` (login only). A key is `sg_` and 256 random bits in base64url, shown once; only its SHA-256 is stored, with a 7-character hint; at most 10 per account; last use written at most once a minute; Flyway V4 `api_key` (cascades with the account). Bearer tokens starting with `sg_` go to the key provider through Spring Security's `authenticationManagerResolver`, anything else to the JWT provider; a JWT grants `SESSION`, a key `KEY`, and a key may only read projects and save versions (403 with `insufficient_scope` otherwise, 401 for an unknown or deleted key). Found by the tests: Spring Security 7 rethrows `AuthenticationServiceException` from the bearer filter, so a key checked while the database is down gave 500; the filter now hands it to the entry point, which answers 503. Web: an "API keys" section on the account page (make by name, the value shown once with copy and a curl example, last use, delete after asking). Tests: 11 new API tests (9 HTTP on PostgreSQL: once-only value, hint, read and save with a key, 403 on every other endpoint, 401 after delete, open endpoints, limit, isolation, cascade; 503 switched off and with the database down), 7 new Vitest tests, a Playwright test that makes a key, saves a version with it through the site's `/api`, meets the 403 and the 401 (CI and live); the live smoke test does the same by curl with the key masked (2026-11-24)
- [x] **8.1** CLI (D-107 to D-110). New module `cli` (picocli 4.7.7; one 1 MB runnable jar, maven-shade-plugin 3.6.2): `schemaguard diff|report|plan <before.sql> <after.sql>`, `lint <schema.sql> <migration.sql>`, `lint --migrations <folder> [files...]` (each file against the schema the earlier ones build; Flyway version order, `R__`/`U` skipped, other names in natural order), `save --project <id> <schema.sql>` (key from `SCHEMAGUARD_KEY`, https only, 409 is "Nothing saved", 120 s for the free host to wake). Every analysis runs offline. `--format text|markdown|json`: text is the engine's golden output, Markdown the web app's download layout, JSON the API's response bodies field for field (checked against `web/openapi.json` by `ContractTest`, mutation-checked). `--fail-above <score or level>`; exit codes 0 done, 1 risk above the limit (or a statement PostgreSQL would refuse), 2 bad input, 3 API failure, 4 bug; diagnostics on standard error as `file:line:column: severity: message [code]`. One input may be `-` (standard input); files must be UTF-8 (a byte order mark is dropped). The Dockerfile copies `cli/pom.xml` (the parent lists every module). Tests: 82 (golden output byte for byte, every format, thresholds, folders, the contract, `save` against a real HTTP server incl. timeout and refused connection, the key never printed); CI runs the built jar on the golden fixture and on SchemaGuard's own migrations (`--fail-above low`) and keeps it as an artifact; the live smoke test saves a version through the live site with the CLI and the throwaway account's key. Found by the tests: adding a column without `lock_timeout` scores 10 (SG-R111), so a test's "safe" migration was not (2026-11-25)
- [x] **8.2** GitHub Action and release workflow (D-111 to D-113). `action/action.yml` (composite) runs `action/schemaguard.sh`: inputs `migrations` or `schema`, `files` (changed or all), `fail-above` (default high), `comment`, `cli-version`/`cli-jar`, `api-key` + `project` (save the schema on pushes to the default branch); outputs `score`, `level`, `exit-code`, `report`. Inputs arrive only as environment variables; Java 21 from the PATH or the runner's `JAVA_HOME_21_*` (the job's JAVA_HOME is left alone); the jar is the release in the action's tag, checked against its SHA-256. On a pull request: fetches only the base commit, checks the migrations it adds or changes (directly in the folder, not `R__`/`U`), turns diagnostics into annotations, writes the report to the job summary and to one comment found by a hidden marker and updated on each push (cut at 60,000 characters; a fork's read-only token gives a warning, not a failure). Release workflow: on publishing a release (or by hand), checks tag = pom version, builds and tests the CLI, attaches the jar and `.sha256`. Tests: `action/test/test.sh` (34 cases on throwaway git repositories with the real jar and stand-ins for `gh` and `curl`: comments posted, updated across pages, corrected, skipped; annotations; long reports; pushes; schema files; saving; downloads and checksums; Java selection; input mistakes), shellcheck-clean; CI runs it, then the action itself on our migrations (passes) and on a risky fixture (fails with 1/60/HIGH). The comment on a real pull request is 8.3's test (2026-11-26)
- [x] **8.3** Dogfooding (D-114, D-115). `.github/workflows/schemaguard.yml` runs on every pull request: builds the CLI from it and runs `./action` on `api/src/main/resources/db/migration` with `fail-above: low`. Seen working on pull request #4 (closed unmerged): a `CHECK` constraint added without `NOT VALID` got the report as a comment from github-actions and a failed check (64/100); the fix the report advises (`NOT VALID` under a lock timeout, `VALIDATE CONSTRAINT` in a second migration) turned the same comment into a pass (0/100), with CI green, Flyway applying both on PostgreSQL. Found on the way: Flyway's default transactional lock makes `CREATE INDEX CONCURRENTLY` (SG-R101's advice) wait forever (reproduced; `pg_locks` showed the build waiting on Flyway's own transaction), so SG-R101's advice now names `postgresql.transactional.lock=false` and the direct connection it needs; SchemaGuard's own Flyway kept the default until 9.2 (D-118). Also found: the jar pattern `*-SNAPSHOT.jar` in CI would have failed on a release version (now `schemaguard-cli-*.jar`). And a reminder: removing a migration from `src` leaves Maven's copy in `target/classes`, which Flyway still applies (2026-11-26)
- [x] **9.1** Rate limits (D-116, D-117). First the client address, measured live: a temporary hidden endpoint (`ForwardedProbeController`) and workflow (`probe.yml`) echoed the address headers for calls made directly and through Vercel, each also with forged values; both are removed. Findings: Render's proxies append the real caller as the last `X-Forwarded-For` entry after anything the caller sent; through Vercel the connecting address is Vercel's (changing AWS addresses) and `X-Vercel-Forwarded-For` names the visitor, overwriting a forged one, but a forged one sent straight to Render passes through; Cloudflare refuses a forged `CF-Connecting-IP` (403). Then a filter that runs before all others (before the body is read): analyses (five endpoints plus saving a project version) 60 a minute, saved analyses 30 an hour, sign-ups 20 an hour, logins 30 per 10 minutes. Bucket4j 8.23.0 token buckets (greedy refill) in a bounded Caffeine cache (100,000, an hour after last use); each request takes from the visitor's bucket and from the connecting address's bucket at 10 times the rate, which holds forged visitor headers to 10x; a refusal by the cap is not charged to the visitor. Keys: IPv4 canonical, IPv6 per /64, IPv4-mapped as IPv4, never resolved as host names; the decoded path is matched (`/api/v1/%64iff` counts). Answers: `X-RateLimit-Limit`, `X-RateLimit-Remaining`; 429 with `Retry-After` and a problem document (`SG-API-429`) saying how long to wait. Settings as `60/1m` (bad values stop startup with a clear message; env vars such as `SCHEMAGUARD_RATE_LIMITS_ANALYSES` work). OpenAPI lists the 429s; `GET /rules` moved to `RulesController` so it no longer claims 400/413/422. Tests: 27 new API tests (rates, address parsing with the measured header shapes, buckets on a hand-moved clock, a real server with small limits, forged Vercel headers, encoded paths, and a check that every POST endpoint is limited or named as needing no limit); tests switch limits off in `src/test/resources/config/application.properties`. Web: a recorded 429 fixture and a test that the wait is shown. Live smoke test: a save direct and one through Vercel from the same runner, the second must report fewer left (2026-11-26)
- [x] **9.2** Migrations over a direct connection (D-118). Flyway now runs on its own connections (a plain driver data source, not the requests' pool) with a session-level lock (`postgresql.transactional.lock=false`) and `SET lock_timeout` (10 s, `migration-lock-timeout`) on each. Where: `migration-url` if set, else Neon's direct host when the URL is its pooled endpoint (`ep-...-pooler...neon.tech` without `-pooler`, Neon's documented naming; checked 2026-11-27), else the same URL; the choice is logged at startup without the password. So the owner never had to say whether the Render secret is pooled. Tests on real PostgreSQL: a test-only `CREATE INDEX CONCURRENTLY` after the service's migrations builds a valid index under the session lock; under Flyway's default lock the same build waits on Flyway's own transaction until the lock timeout ends it (D-115 kept as a test); `migration-url` pointing nowhere gives a 503 and leaves the requests' database untouched; URL derivation for Neon pooled, direct and other hosts. Found: Flyway refuses a file mixing `SET` with `CONCURRENTLY`, so a concurrent index goes alone in its own migration (the lint scores it 0 either way). Live: the smoke test's saves run the migration check over the new connection (2026-11-27)

## Phases

| Phase | Deliverable | Status |
| --- | --- | --- |
| 0 | Blueprint | Done |
| 1 | Scaffold, CI, lexer, parser, schema model, catalog | Done (2026-11-13) |
| 2 | Diff engine with rename detection (plus SQL writer) | Done (2026-11-14) |
| 3 | Rules engine, lock knowledge base, scoring | Done (2026-11-14) |
| 4 | Migration planner, SQL generation, real-database oracle tests, script linter | Done (2026-11-15) |
| 5 | REST API (Spring Boot), persistence, permalinks, first deploy (v0.1) | Done (2026-11-18); live |
| 6 | Web UI: editors, diff, report, ER diagram, example gallery (v0.5) | Done (2026-11-20), live; `v0.5.0` released 2026-11-22 |
| 7 | Auth, projects, history, trends, API keys | Done (2026-11-24), live |
| 8 | CLI, GitHub Action, dogfooding | Done (2026-11-26): seen working on pull request #4 |
| 9 | Hardening and launch (v1.0) | In progress: 9.1 and 9.2 done, live (2026-11-27); next 9.3 |

## Working rules

- One slice = one feature + its tests + one commit + one push. Update this file in the same commit.
- Commits are authored by the owner: run `git config user.name "Hanumanth Raghavendra"` and
  `git config user.email raghavendra.boienpally@gmail.com` in a fresh clone before the first
  commit (Claude goes in the `Co-Authored-By` trailer only). All earlier commits were rewritten
  to this author on 2026-11-18. License: MIT (`LICENSE`).
- Never commit on a red build. `./mvnw -B -ntp verify` must pass first.
- Pin dependency versions only after checking the registry, never from memory.
- Decisions go in `docs/DECISIONS.md`; explanations for interviews go in `docs/INTERVIEW.md`.
- Web app (`web/`, Node 22): `npm ci`, then `npm run lint -- --max-warnings 0`, `npm run typecheck`,
  `npm test`, `npm run build`, all green before a commit. After any API change: run the Maven build
  with `-Dgolden.update=true` (rewrites `web/openapi.json`), then `npm run gen:api` in `web/`, and
  commit both files. Re-record `web/src/test/fixtures/` from a local API when a response changes.
- In tests, do not use `@CsvSource` for SQL with its default quote character: it mangles `'`.
  Use the `rows(...)` helper with `@MethodSource`, or set `quoteCharacter = '"'`.
- Golden files: `DiffGoldenTest` compares with `golden/evolution/expected-diff.txt`. After an
  intended output change: `./mvnw -B -ntp verify -Dgolden.update=true`, then review the
  file's `git diff` before committing. The same switch rewrites `expected-report.txt` and
  `docs/RULES.md` (`RiskReportTest`), and `expected-plan-*.sql` (`PlannerTest`).
- Rules: one class per rule under `rules/<category>/`, registered in `RuleRegistry`. A rule id
  is never reused. Every rule needs a `fires` and a `quiet` case in `RulesTest` (a test checks).
- A fact about locks or rewrites goes into `Operation` / `TypeChange` only after
  `scripts/measure-locks.sh` has measured it; add the case to the script, not just to the enum.

## Known issues and open questions

- **Planner:** the oracle compares schemas on empty tables; it does not yet prove that rows
  survive (renames, the safe NOT NULL route). Not planned: enum value removal or reorder,
  partitioning changes, a new generation expression, temporary tables. A rename candidate
  needs an identical column definition, so "renamed and made NOT NULL" cannot be confirmed.
  An identity column's hidden sequence is not modelled.
- **Web:** the risk report judges the schemas as written (`/api/v1/report` takes no renames), so
  a confirmed rename still shows "Column is dropped" under Risks. The page says so and offers
  "Check the plan as a migration", which lints the generated plan and shows its real risks
  (D-079). Renames on `/report` would make the Risks tab exact; not planned yet. Problem
  extension members (`code`, `diagnostics`) are typed by hand in `web/src/api/client.ts`.
- **Web, shared links:** the delete key lives in the sharer's browser only (localStorage); a
  cleared browser cannot delete its links, which then expire after 30 days. Links are not tied
  to accounts yet.
- **Deploys:** a push that changes `render.yaml` makes Render sync the Blueprint, and that sync
  deploys at once without waiting for the checks (seen on 2026-11-22: the 7.1 commit went live
  three minutes before CI finished, then again after it passed). Push `render.yaml` changes on
  their own, once the code they rely on is live, so a red commit cannot reach production this way.
- **Projects:** a version is a whole schema; a migration script cannot be saved as one yet (lint it
  in the analyser). Scores on the timeline are as computed when each version was saved; the analyser
  re-scores with today's rules, so the two can differ after a rule changes. Shared links are not
  tied to projects.
- **Accounts:** no email verification and no password reset (both need an email service);
  sign-up therefore says when an email is taken (409). Access tokens stay valid up to 15 minutes
  after logout (they are not looked up per request). A user can only end the current session;
  "log out everywhere" is not offered yet (deleting the account does end all sessions).
- **GitHub Action:** Linux and macOS runners only (Bash). It cannot comment on a pull request
  from a fork (read-only token); the summary and the pass or fail still work. It is in a
  subfolder, so it cannot be listed on the GitHub Marketplace (that needs `action.yml` at a
  repository's root); a separate repository could do that in Phase 9.
- **CLI:** `plan` cannot confirm renames yet (the web app can; a rename stays a drop and an add
  with a caution). Rules know PostgreSQL 16 only, so there is no `--pg` option. `save` takes a
  whole schema, not a migration. Identifiers with non-ASCII letters may show as `?` on a Windows
  console that is not set to UTF-8 (the engine's own text is ASCII).
- **Flyway (D-118):** a `CREATE INDEX CONCURRENTLY` migration must hold that one statement alone
  (Flyway refuses it mixed with `SET` or other statements). Migrations behind a pooler other than
  Neon's need `SCHEMAGUARD_PERSISTENCE_MIGRATION_URL`, or they take a session lock through the pooler.
- **API keys:** a key reaches all of its account's projects (no per-project scope) and never
  expires; it ends only when deleted. Checking a key costs one indexed query per request (no
  cache), fine at today's traffic.
- **API:** rate limits are kept in memory, per instance: a restart forgets the counts, and a
  second instance would count separately (there is one). The web app's visitors share Vercel's
  connecting addresses, so a very busy day could reach the 10x connecting cap for one of them;
  Vercel spreads requests over many addresses, and the factor is a setting. Storage stays capped
  at 200 MB in total besides (507 beyond it). The API's coverage gate counts the
  permalink and account tests, which need `SG_PG_ORACLE=1` and a PostgreSQL server; without
  them `verify` on `api` can fall under 80%. The Docker image is verified in CI only (no Docker on the
  development machine).
- **Performance:** linting costs about 1.6 ms per statement at 400 tables (whole-schema diff per
  statement, linear in schema size). Fine for real migrations; a script of thousands of
  statements against a large schema takes seconds, hence the API limits in Phase 5.
- **Linter:** a statement the parser does not model (functions, views, triggers, `DO` blocks)
  is listed as not analysed, never guessed. It does not know which helper constraint is a
  helper beyond "added and dropped in the same script". `ALTER TABLE ... ALTER COLUMN ... SET
  GENERATED ... RESTART` (identity sequence options) is still unsupported.
- **Rules:** lock facts are PostgreSQL 16 only. No table sizes, so a rewrite of a 10-row table
  scores like one of a billion rows. `timestamp` to `timestamptz` is always called a rewrite
  (it is none when the session is in UTC). `TypeChange.kind()` is a judgement, not a
  measurement. A type change on an indexed column that needs no rewrite may still rebuild the
  index; not modelled. `SET NOT NULL` proof needs the exact form `CHECK (col IS NOT NULL)`.
  Volatility of user-defined and extension functions is unknown (rule SG-R203, half weight).
- **Not read yet:** views, functions, triggers, policies, `CREATE TABLE ... PARTITION OF` and
  `ATTACH PARTITION`, `EXCLUDE` constraints, typed tables, `LIKE`. Each is reported by name.
- **Diff:** rename candidates need an identical definition; a column renamed *and* retyped is
  reported as a drop and an add only. Sequences have no rename detection. An index whose
  definition matches a dropped one under a new name is always called a rename.
- **Expressions are text.** PostgreSQL rewrites some expressions when it stores them (`IN (...)`
  becomes `= ANY (ARRAY[...])`, `BETWEEN` becomes two comparisons, `LIKE` becomes `~~`).
  A script and its dump can therefore differ beyond what the fingerprint hides. The diff must
  report such a difference as "possibly cosmetic", never hide it.
- `Expressions.referencedColumns`, `renameColumn` and `indexColumnName` are token scans, not a
  full expression parser: a column that shares its name with a type used in `CAST(x AS name)`
  is a known false positive.
- The catalog does not track schemas (`CREATE SCHEMA` is ignored) or check that a column's
  type exists. Identity columns' hidden sequences are not modelled, so they do not reserve a name.
- `ALTER TYPE ... RENAME VALUE` does not rewrite column defaults that mention the old label.
- A foreign key is bound to the earliest matching key constraint in creation order. An index
  promoted with `USING INDEX` counts from the moment it is promoted, not from its creation.
- Identifier truncation and generated-name shortening count characters, PostgreSQL counts
  bytes: names with non-ASCII characters near 63 bytes can differ.
- `ALTER TABLE`: one action the parser does not model (`SET STATISTICS`, `ENABLE ROW LEVEL
  SECURITY`, `ATTACH PARTITION`, ...) makes the whole statement "unsupported" (see D-019).
- `ALTER TABLE ... ADD CONSTRAINT name NOT NULL col` becomes `SetNotNull` and loses the name.
- `ALTER TABLE` accepts `RENAME` mixed with other actions; PostgreSQL requires it alone.
- Any `ALTER ... OWNER TO role` statement is ignored as a whole, by its last three tokens.
- Lexer: `U&` Unicode-escape syntax is reported as unsupported; adjacent string constants split
  across lines are not concatenated.
- PostgreSQL 18 temporal keys (`WITHOUT OVERLAPS`, `PERIOD`) are reported as syntax errors.
- `Identifiers.RESERVED` lists fully reserved keywords only. Before generating SQL in phase 4,
  check it against the server's `pg_get_keywords()`.
