# SchemaGuard: progress

> **To resume:** read this file, then start at "Next step". Do not re-plan; the plan is fixed below.

- **Current phase:** 7 (accounts, projects, history, trends, API keys), not started. Phase 6 is done: web app live at https://schemaguard-v1.vercel.app (renamed from schemaguard-nu on 2026-11-22), released as `v0.5.0` (tagged by the owner on 2026-11-22). Phases 1 to 5 are complete; v0.1 is live at https://schemaguard-api-c00y.onrender.com (Render free tier, Singapore; saved analyses in Neon).
- **Last updated:** 2026-11-22
- **Build command (repo root):** `./mvnw -B -ntp verify` (Windows: `mvnw.cmd -B -ntp verify`)
- **State:** build green; engine 1,714 tests (276 plans run on a real PostgreSQL 16 in CI), API 49 tests (permalinks tested on real PostgreSQL); web 51 tests (Vitest) and 1 end-to-end test (Playwright); line coverage engine 99.3% (gate 85%), API 96.0% (gate 80%); CI builds the Docker image, starts it and calls it; the live smoke test passes against the deployed service
- **Size:** 337 files in 64 folders; 15,057 lines of main Java, 8,010 lines of test Java, 3,535 lines of TypeScript (plus 1,118 generated) (Trackly reference: 249 files, 74 folders, 5,888 Java + 11,781 JS lines)

## Next step

**Live service.** Web app https://schemaguard-v1.vercel.app (Vercel, redeploys on every push to `main`).
API https://schemaguard-api-c00y.onrender.com (Swagger UI at `/swagger-ui.html`).
Render builds the `Dockerfile` from `main` after the CI checks pass, and only when a push changes
what goes into the image (`render.yaml`: `autoDeployTrigger: checksPass`, `buildFilter`). Neon's
connection string is the dashboard secret `SCHEMAGUARD_PERSISTENCE_URL` (never in the repository).
After any deploy, run the live smoke test (manual workflow `live-smoke.yml`; checks health, docs,
all analyses, save/open/delete, CORS). From the Claude container (`gh workflow run` is blocked
there, the REST call works):
`gh api -X POST repos/Hanumanth-Raghavendra/schemaguard/actions/workflows/live-smoke.yml/dispatches -f ref=main -f 'inputs[persistence]=required'`
(or GitHub > Actions > Live smoke test > Run workflow). It prints the jar's build time, which
changes only when the jar is rebuilt (Docker layer cache). Both deploy rules were verified on
2026-11-18: a `pom.xml` change deployed 4 s after CI went green; a docs-only push deployed
nothing. Render reports each deploy to GitHub (repository > Deployments).

**Phase 6: web UI (v0.5), live.** Requirements (Blueprint, tier 2 "Web app"): analyse without login
in two code editors, visual diff, risk report, shareable permalink; interactive ER diagram with
changed tables and columns highlighted; example gallery of real-world scenarios, so a recruiter
sees results in one click; export of the report as Markdown or JSON. All four slices are done (see Done); released as `v0.5.0`. Live at https://schemaguard-v1.vercel.app. The
app lives in `web/` (Vite 8, React 19, TypeScript 5.9, Tailwind 4, Monaco; see D-075).

**Phase 7: accounts, projects, history, trends, API keys.** First commit of Phase 7, after the tag:
set the version to `0.6.0-SNAPSHOT` in `pom.xml`, `engine/pom.xml` and `api/pom.xml`, and in
`web/` run `npm version 0.6.0-SNAPSHOT --no-git-tag-version`, so builds after the tag are not
mistaken for 0.5.0 (D-087).

**Phase 7 scope.** Blueprint tier 3: sign-up and login
with JWT access and refresh tokens; projects with schema versions on a timeline, a risk trend chart
and saved reports; per-user API keys for the CLI and CI. Done when a user can save schema versions
and see the risk trend. Decide first, and record in DECISIONS:

- **Where the refresh token lives.** The web app (vercel.app) and the API (onrender.com) are
  different sites, so an httpOnly refresh cookie set by the API is a third-party cookie, which
  browsers block more and more. The likely answer is to serve the API through the web app's own
  origin with a Vercel rewrite (`/api/*` to the Render URL), which makes the cookie first-party
  and removes CORS for the web app. Check Vercel's external rewrites and their limits first.
- **Libraries.** Spring Security 7 (in the Boot 4.1 BOM) as an OAuth2 resource server for access
  tokens; Argon2id password hashing from spring-security-crypto (its Bouncy Castle dependency);
  refresh tokens random, stored hashed, rotated on use, with reuse detection. Check versions.
- **Slices.** 7.1 accounts (Flyway V2 users and refresh tokens; sign-up, login, refresh, logout;
  web pages). 7.2 projects and schema versions (save a version, diff it with the previous one,
  timeline). 7.3 risk trend chart and saved reports. 7.4 API keys (hashed, shown once, revocable).

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
| 7 | Auth, projects, history, trends, API keys | Next |
| 8 | CLI, GitHub Action, dogfooding | Not started |
| 9 | Hardening and launch (v1.0) | Not started |

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
  cleared browser cannot delete its links, which then expire after 30 days.
- **API:** no rate limiting yet (one free instance). Per-IP limits need the real client address,
  and which `X-Forwarded-For` entry Render's proxy guarantees is not verified yet; a limit keyed
  on the wrong entry would either be spoofable or throttle every visitor as one. Settle that in
  Phase 9 (hardening). Meanwhile saving is the abusable part: storage is capped at 200 MB in
  total, so abuse fills it and saving answers 507 until analyses expire. No auth until Phase 7. The API's coverage gate
  counts the permalink tests, which need `SG_PG_ORACLE=1` and a PostgreSQL server; without
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
