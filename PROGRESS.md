# SchemaGuard: progress

> **To resume:** read this file, then start at "Next step". Do not re-plan; the plan is fixed below.

- **Current phase:** 1 (scaffold, CI, lexer, parser, schema model)
- **Last updated:** 2026-11-12
- **Build command (repo root):** `./mvnw -B -ntp verify` (Windows: `mvnw.cmd -B -ntp verify`)
- **State:** build green, 222 tests, engine line coverage 99.6%, branch 94.7% (gate: 85% line)
- **Size:** 49 files in 16 folders; 2716 lines of main Java, 1517 lines of test Java (Trackly reference: 249 files, 5,888 Java + 11,781 JS lines)

## Next step

**1.4b Remaining DDL statements.** In `engine/.../parser/StatementParser.java`, replace the
"unsupported" fallthrough for these, one at a time, each with tests in `ParserTest`:

1. `CREATE TYPE name AS ENUM (...)` -> new model record `EnumType(QualifiedName, List<String> labels)`.
2. `CREATE SEQUENCE` -> model record `Sequence` (start, increment, min, max, cache, cycle, owned by).
3. `CREATE [UNIQUE] INDEX [CONCURRENTLY] [IF NOT EXISTS] name ON [ONLY] table [USING method] (...)`
   `[INCLUDE (...)] [NULLS [NOT] DISTINCT] [WITH (...)] [TABLESPACE x] [WHERE predicate]`
   -> model record `Index` with `IndexColumn` (column or expression, sort order, nulls order, opclass).
4. `ALTER TABLE [IF EXISTS] [ONLY] name action [, ...]` -> `Statement.AlterTable` holding a sealed
   `AlterAction` list: `AddColumn`, `DropColumn`, `AlterColumnType` (with USING), `SetDefault`,
   `DropDefault`, `SetNotNull`, `DropNotNull`, `AddConstraint` (reuse `parseTableConstraint`, which
   already accepts `NOT VALID`), `DropConstraint`, `ValidateConstraint`, `RenameColumn`,
   `RenameTable`, `RenameConstraint`. Actions not listed stay "unsupported feature" warnings.
5. `DROP TABLE | INDEX | TYPE | SEQUENCE [IF EXISTS] names [CASCADE | RESTRICT]`.

**1.5 Catalog.** `engine/.../model/Catalog.java` (tables, enums, sequences, indexes by
`QualifiedName`) and `engine/.../catalog/CatalogBuilder.java`, which applies statements in order.
It must also: expand `serial` types into integer + sequence + default; resolve unqualified
user-defined types against known enums (see D-012); report "table does not exist" style errors.

**1.6 Golden tests.** `engine/src/test/resources/golden/*.sql` with expected catalog snapshots.
Phase 1 is done when a real `pg_dump --schema-only` file builds a catalog with no "unsupported"
warnings for tables, indexes, enums and sequences.

## Done

- [x] **Phase 0** Blueprint (2026-11-12)
- [x] **1.1** Scaffold: Maven multi-module build, Maven wrapper, GitHub Actions CI, coverage gate, docs (2026-11-12)
- [x] **1.2** Lexer (2026-11-12)
- [x] **1.3** Schema model: `QualifiedName`, `DataType`, `Column`, sealed `Constraint` family, `Table`, `Expression` (2026-11-12)
- [x] **1.4a** Parser: statement splitter, token cursor, type parser, expression renderer, full `CREATE TABLE` (2026-11-12)

## Phases

| Phase | Deliverable | Status |
| --- | --- | --- |
| 0 | Blueprint | Done |
| 1 | Scaffold, CI, lexer, parser, schema model | In progress (1.1 to 1.4a done) |
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
- In tests, do not use `@CsvSource` for SQL: its quote handling mangles `'` and `"`. Use the
  `rows(...)` helper with `@MethodSource` as in `ParserTest`.

## Known issues and open questions

- License not chosen yet (see D-007).
- Lexer: `U&` Unicode-escape syntax is reported as unsupported; adjacent string constants split
  across lines are not concatenated. Both are rare in DDL.
- Parser: PostgreSQL 18 temporal keys (`WITHOUT OVERLAPS`, `PERIOD`) are reported as syntax
  errors rather than as unsupported features.
- Parser: unnamed constraints keep an empty name. PostgreSQL's generated names
  (`t_pkey`, `t_col_key`, ...) are derived in phase 2 and verified against a real server in phase 4.
- Expressions are compared as canonical text. Two dumps from PostgreSQL compare reliably; a
  hand-written expression may differ from the server's rewritten form (extra casts). The diff
  engine must flag such differences as "possibly cosmetic" (phase 2).
- `Identifiers.RESERVED` lists fully reserved keywords only. Before generating SQL in phase 4,
  check it against the server's `pg_get_keywords()` in the oracle tests.
