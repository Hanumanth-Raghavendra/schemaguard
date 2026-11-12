# SchemaGuard: progress

> **To resume:** read this file, then start at "Next step". Do not re-plan; the plan is fixed below.

- **Current phase:** 1 (scaffold, CI, lexer, parser, schema model)
- **Last updated:** 2026-11-12
- **Build command (repo root):** `./mvnw -B -ntp verify` (Windows: `mvnw.cmd -B -ntp verify`)
- **State:** build green locally, 67 tests, engine line coverage 99.7% (gate: 85%)

## Next step

**1.3 Schema model.** In `engine/src/main/java/.../engine/model/`, add immutable records:
`QualifiedName` (schema + name, case-exact), `DataType` (name, modifiers, array dimensions),
`Column` (name, type, nullable, default expression text, identity/generated), `Constraint`
(sealed: `PrimaryKey`, `ForeignKey`, `Unique`, `Check`), `Index`, `EnumType`, `Sequence`,
`Table`, and `Schema` (the root, with lookup by qualified name). Unit tests for equality and lookup.

**1.4 Parser.** `engine/.../parser/`: a `TokenCursor`, a statement splitter (on `SEMICOLON`,
skipping `META_COMMAND` and `COPY_DATA`), and a recursive-descent parser for `CREATE TABLE`
first. Any statement it does not understand becomes an `SG-PARSE` "unsupported statement"
diagnostic (never skipped silently). Then `CREATE TYPE ... AS ENUM`, `CREATE SEQUENCE`,
`CREATE INDEX`, `ALTER TABLE ... ADD CONSTRAINT / ALTER COLUMN`, `DROP`.

**1.5 Golden tests.** `engine/src/test/resources/golden/*.sql` with expected model snapshots;
phase 1 is done when a real `pg_dump --schema-only` file parses into the model.

## Done

- [x] **Phase 0** Blueprint (2026-11-12)
- [x] **1.1** Scaffold: Maven multi-module build, Maven wrapper, GitHub Actions CI, coverage gate, docs (2026-11-12)
- [x] **1.2** Lexer with 67 tests (2026-11-12)

## Phases

| Phase | Deliverable | Status |
| --- | --- | --- |
| 0 | Blueprint | Done |
| 1 | Scaffold, CI, lexer, parser, schema model | In progress (1.1, 1.2 done) |
| 2 | Diff engine with rename detection | Not started |
| 3 | Rules engine, lock knowledge base, scoring | Not started |
| 4 | Migration planner, SQL generation, real-database oracle tests (Testcontainers) | Not started |
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

## Known issues and open questions

- License not chosen yet (see D-007).
- Lexer limitations, both rare in DDL: `U&` Unicode-escape syntax is reported as unsupported;
  adjacent string constants split across lines are not concatenated.
