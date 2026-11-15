# SchemaGuard: progress

> **To resume:** read this file, then start at "Next step". Do not re-plan; the plan is fixed below.

- **Current phase:** 4 (migration planner): 4.1 done, 4.2 next. Phases 1, 2 and 3 are complete.
- **Last updated:** 2026-11-15
- **Build command (repo root):** `./mvnw -B -ntp verify` (Windows: `mvnw.cmd -B -ntp verify`)
- **State:** build green, 1,460 tests (264 of them run a plan on a real PostgreSQL 16, also in CI), engine line coverage 99.4%, branch 94.5% (gate: 85% line)
- **Size:** 190 files in 36 folders; 11,519 lines of main Java, 5,963 lines of test Java (Trackly reference: 249 files, 74 folders, 5,888 Java + 11,781 JS lines)

## Next step

**Phase 4.2: read the user's own migration script.** Today the report and the plan start from
two schemas. A user who pastes `before.sql` plus a `migration.sql` gets judged on the direct
route even if the script already says `CONCURRENTLY`. Fix that, then Phase 4 is complete.

1. Parser: read the statements the planner writes and the parser still calls unsupported:
   `ALTER INDEX ... RENAME TO`, `ALTER COLUMN ... SET GENERATED`, `ALTER COLUMN ... DROP
   EXPRESSION`, `SET LOGGED` / `SET UNLOGGED`, `INHERIT` / `NO INHERIT`, `ALTER SEQUENCE` with
   options. Apply each in `CatalogBuilder`. Done when `PlanOracleTest` has no skipped case in
   `theEngineAgreesThatThePlanReachesTheNewSchema` (21 are skipped now).
2. New `engine/.../lint/`: `MigrationLinter.lint(Catalog before, List<Statement>)`. Map each
   statement (and each `AlterAction`) to its `Operation`, with the catalog as it is *at that
   statement* for context (table created earlier in the script = no rows; valid CHECK present =
   `SET_NOT_NULL_WITH_VALID_CHECK`). Reuse `RuleInfo` texts: a finding per statement whose
   operation blocks writes while its work grows with the table, plus the data-loss statements
   (`DROP TABLE`, `DROP COLUMN`). Output: findings with the statement's `SourceSpan` (line
   numbers for the UI and the GitHub Action).
3. Tests: the evolution `migration.sql` gets a golden lint report; every safe plan from
   `PlanOracleTest`, fed back into the linter, must produce no blocking finding without a
   caution (this ties planner and linter together).
4. Then **Phase 5** (REST API): Spring Boot module `api`, endpoints `POST /api/v1/diff`,
   `/report`, `/plan`, `/lint`; DTOs; OpenAPI; deploy to Render.

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

## Phases

| Phase | Deliverable | Status |
| --- | --- | --- |
| 0 | Blueprint | Done |
| 1 | Scaffold, CI, lexer, parser, schema model, catalog | Done (2026-11-13) |
| 2 | Diff engine with rename detection (plus SQL writer) | Done (2026-11-14) |
| 3 | Rules engine, lock knowledge base, scoring | Done (2026-11-14) |
| 4 | Migration planner, SQL generation, real-database oracle tests | 4.1 done (2026-11-15), 4.2 next |
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
  New tables are created in name order (a child with `INHERITS` before its parent would fail).
  An identity column's hidden sequence is not modelled.
- The parser accepts a reserved word as a constraint name (`CONSTRAINT both`); PostgreSQL does not.
- **Rules judge the direct route** (one plain statement per change), not a migration script the
  user wrote: a script that already says `CONCURRENTLY` is still reported. Phase 4, step 6.
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
