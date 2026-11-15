# SchemaGuard: progress

> **To resume:** read this file, then start at "Next step". Do not re-plan; the plan is fixed below.

- **Current phase:** 5 (REST API). Phases 1 to 4 are complete.
- **Last updated:** 2026-11-15
- **Build command (repo root):** `./mvnw -B -ntp verify` (Windows: `mvnw.cmd -B -ntp verify`)
- **State:** build green, 1,701 tests, none skipped (276 plans run on a real PostgreSQL 16, also in CI), engine line coverage 98.9%, branch 93.8% (gate: 85% line)
- **Size:** 202 files in 39 folders; 12,751 lines of main Java, 6,529 lines of test Java (Trackly reference: 249 files, 74 folders, 5,888 Java + 11,781 JS lines)

## Next step

**Phase 5: REST API + first deploy (v0.1).** New Maven module `api` (Spring Boot 3.x, Java 21),
depending on `engine`. Check the latest Spring Boot version on Maven Central before pinning.

1. Endpoints, all `POST`, JSON in and out, no state yet:
   `/api/v1/diff` (before, after) -> changes; `/api/v1/report` (before, after) -> findings +
   score; `/api/v1/plan` (before, after, route, confirmed renames, lock timeout) -> steps +
   unplanned; `/api/v1/lint` (schema, migration) -> per-statement findings with line numbers.
   Each response also carries the text form (`ChangeFormatter`, `ReportFormatter`, plan
   `toSql()`, `LintFormatter`) so a client can show it without rendering.
2. DTOs as records in `api/.../dto`, mapped from engine records; never expose engine types.
   Parse diagnostics come back as 422 with line/column; size limit on input (e.g. 1 MB).
3. springdoc-openapi for `/v3/api-docs` + Swagger UI. Validation with Bean Validation.
   `GET /api/v1/rules` lists the rules (ids, titles, why, fix) incl. `ScriptRules.ALL`.
4. Tests: `@WebMvcTest` per controller + one `@SpringBootTest` end-to-end on the evolution
   fixture. Keep the engine's coverage gate; give `api` its own (80% line).
5. Dockerfile (multi-stage, JRE 21, non-root) and `render.yaml`; deploy to Render free tier;
   CORS for the future Vercel web app; health endpoint `/actuator/health`.

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

## Phases

| Phase | Deliverable | Status |
| --- | --- | --- |
| 0 | Blueprint | Done |
| 1 | Scaffold, CI, lexer, parser, schema model, catalog | Done (2026-11-13) |
| 2 | Diff engine with rename detection (plus SQL writer) | Done (2026-11-14) |
| 3 | Rules engine, lock knowledge base, scoring | Done (2026-11-14) |
| 4 | Migration planner, SQL generation, real-database oracle tests, script linter | Done (2026-11-15) |
| 5 | REST API (Spring Boot), persistence, permalinks, first deploy (v0.1) | Not started |
| 6 | Web UI: editors, diff, report, ER diagram, example gallery (v0.5) | Not started |
| 7 | Auth, projects, history, trends, API keys | Not started |
| 8 | CLI, GitHub Action, dogfooding | Not started |
| 9 | Hardening and launch (v1.0) | Not started |

## Working rules

- One slice = one feature + its tests + one commit + one push. Update this file in the same commit.
- Never commit on a red build. `./mvnw -B -ntp verify` must pass first.
- Pin dependency versions only after checking the registry, never from memory.
- Decisions go in `docs/DECISIONS.md`; explanations for interviews go in `docs/INTERVIEW.md`.
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
