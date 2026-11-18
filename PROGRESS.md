# SchemaGuard: progress

> **To resume:** read this file, then start at "Next step". Do not re-plan; the plan is fixed below.

- **Current phase:** 6 (web UI): 6.1 done (editors, risks, changes, plan, migration check), 6.2 next. Phases 1 to 5 are complete; v0.1 is live at https://schemaguard-api-c00y.onrender.com (Render free tier, Singapore; saved analyses in Neon).
- **Last updated:** 2026-11-18
- **Build command (repo root):** `./mvnw -B -ntp verify` (Windows: `mvnw.cmd -B -ntp verify`)
- **State:** build green; engine 1,703 tests (276 plans run on a real PostgreSQL 16 in CI), API 47 tests (permalinks tested on real PostgreSQL); web 23 tests (Vitest); line coverage engine 99.3% (gate 85%), API 95.8% (gate 80%); CI builds the Docker image, starts it and calls it; the live smoke test passes against the deployed service
- **Size:** 305 files in 59 folders; 14,721 lines of main Java, 7,797 lines of test Java, 1,677 lines of TypeScript (plus 1,005 generated) (Trackly reference: 249 files, 74 folders, 5,888 Java + 11,781 JS lines)

## Next step

**Live service.** https://schemaguard-api-c00y.onrender.com (Swagger UI at `/swagger-ui.html`).
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

**Phase 6: web UI (v0.5).** Requirements (Blueprint, tier 2 "Web app"): analyse without login
in two code editors, visual diff, risk report, shareable permalink; interactive ER diagram with
changed tables and columns highlighted; example gallery of real-world scenarios, so a recruiter
sees results in one click; export of the report as Markdown or JSON. 6.1 is done (see Done). The
app lives in `web/` (Vite 8, React 19, TypeScript 5.9, Tailwind 4, Monaco; see D-075).

- **6.2, the next slice: share and export.** "Share" saves through `POST /api/v1/analyses/{kind}`
  (compare mode saves the report, migration mode the lint) and shows the link `/a/{id}`. Add
  `react-router` (check npm first) with routes `/` and `/a/:id`; `/a/:id` reads
  `GET /api/v1/analyses/{id}` and shows the stored request in read-only editors and the stored
  result with the same views. Keep `{id: deleteToken}` in localStorage so the sharer sees a
  "Delete" button (`DELETE` with `X-Delete-Token`); handle 404 (expired or deleted) and 503
  (saving switched off) with plain messages. Add `web/vercel.json` with a rewrite of every path
  to `/index.html`. Export: "Download JSON" (the response as it came) and "Download Markdown"
  (built in the browser from the same data: score, reasons, findings with why and fix, plan SQL).
- **6.3: example gallery and ER diagram.** Gallery: four to six real scenarios (add a column with
  a default, add a foreign key, change a type, rename, add an index, split a table), each loading
  both editors and running the analysis. ER diagram: needs a new endpoint (for example
  `POST /api/v1/model` with `before`/`after`) that returns tables, columns, keys and foreign keys
  with each table and column marked added, removed, changed or unchanged; draw it in SVG (check
  npm for a layout library such as elkjs or dagre before writing one), pan and zoom, highlight
  marks in the change colours.
- **6.4: end to end and deploy.** One Playwright test against a locally started API (jar) and
  `vite preview`, run in CI. Deploy to Vercel from `web/` (root directory `web`, build
  `npm run build`, output `dist`; a production build already calls the live API, see `API_URL` in
  `web/src/api/client.ts`). Then change
  `SCHEMAGUARD_CORS_ALLOWED_ORIGINS` in `render.yaml` to `https://<vercel-host>,http://localhost:5173`
  and push. Change it in `render.yaml`, not only in the Render dashboard: the Blueprint sync
  writes the file's value back over a dashboard edit. Give `live-smoke.yml` an `origin` input and
  check the Vercel origin with it. Put the web address in the README and the Blueprint.
  Phase 6 is done when paste, analyse and share work on the live site and `v0.5.0` is tagged
  (Blueprint). The Claude container cannot push tags (the session's git proxy and the API both
  refuse), so the owner tags: in the repository folder, `git pull`, then
  `git tag -a v0.5.0 -m "SchemaGuard 0.5.0"` and `git push origin v0.5.0` (or GitHub > Releases >
  Draft a new release > tag `v0.5.0` on `main`).

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

## Phases

| Phase | Deliverable | Status |
| --- | --- | --- |
| 0 | Blueprint | Done |
| 1 | Scaffold, CI, lexer, parser, schema model, catalog | Done (2026-11-13) |
| 2 | Diff engine with rename detection (plus SQL writer) | Done (2026-11-14) |
| 3 | Rules engine, lock knowledge base, scoring | Done (2026-11-14) |
| 4 | Migration planner, SQL generation, real-database oracle tests, script linter | Done (2026-11-15) |
| 5 | REST API (Spring Boot), persistence, permalinks, first deploy (v0.1) | Done (2026-11-18); live |
| 6 | Web UI: editors, diff, report, ER diagram, example gallery (v0.5) | In progress (6.1 done) |
| 7 | Auth, projects, history, trends, API keys | Not started |
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

- License not chosen yet (see D-007).
- **Planner:** the oracle compares schemas on empty tables; it does not yet prove that rows
  survive (renames, the safe NOT NULL route). Not planned: enum value removal or reorder,
  partitioning changes, a new generation expression, temporary tables. A rename candidate
  needs an identical column definition, so "renamed and made NOT NULL" cannot be confirmed.
  An identity column's hidden sequence is not modelled.
- The parser accepts a reserved word as a constraint name (`CONSTRAINT both`); PostgreSQL does not.
- **Web:** the risk report does not know about confirmed renames (`/api/v1/report` takes no
  renames; `/plan` does), so a confirmed rename still shows "Column is dropped" under Risks.
  Fix in the API (renames on `/report`) before 6.4. Problem extension members (`code`,
  `diagnostics`) are typed by hand in `web/src/api/client.ts`.
- **API:** no rate limiting yet (one free instance); add per-IP limits before sharing the URL
  widely, especially for saving (storage is capped at 200 MB in total, so abuse fills it and
  saving answers 507 until analyses expire). No auth until Phase 7. The API's coverage gate
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
