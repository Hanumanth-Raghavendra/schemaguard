# SchemaGuard: progress

> **To resume:** read this file, then start at "Next step". Do not re-plan; the plan is fixed below.

- **Current phase:** 1 (scaffold, CI, lexer, parser, schema model)
- **Last updated:** 2026-11-13
- **Build command (repo root):** `./mvnw -B -ntp verify` (Windows: `mvnw.cmd -B -ntp verify`)
- **State:** build green, 371 tests, engine line coverage 99.7%, branch 95.8% (gate: 85% line)
- **Size:** 73 files in 18 folders; 3796 lines of main Java, 2701 lines of test Java (Trackly reference: 249 files, 5,888 Java + 11,781 JS lines)

## Next step

**1.5 Catalog.** New package `engine/src/main/java/.../engine/catalog/`:

1. `model/Catalog.java`: an immutable snapshot of a schema: tables, enums, sequences and indexes,
   each looked up by `QualifiedName` (an index is keyed by its table's schema + its own name).
2. `catalog/CatalogBuilder.java`: starts empty and applies `Statement`s in source order.
   `CreateTable`, `CreateIndex`, `CreateEnum`, `CreateSequence` add objects; `Drop` removes them
   (dropping a table also removes its indexes and the sequences its columns own);
   `AddEnumValue`, `RenameEnumValue`, `AlterSequenceOwner` modify them; `AlterTable` applies each
   `AlterAction` to the table (needs `with...` copy methods on `Table` and `Column`).
   `Ignored`, `Unsupported` and `Invalid` are skipped.
3. Catalog errors are diagnostics with codes `SG-CAT-001...`, never exceptions: relation already
   exists / does not exist (honouring `IF EXISTS` and `IF NOT EXISTS`), column does not exist,
   constraint does not exist, duplicate column.
4. `serial`, `bigserial`, `smallserial` expand into the integer type + NOT NULL + a sequence
   `<table>_<column>_seq` owned by the column + `DEFAULT nextval('<schema>.<seq>'::regclass)`.
5. Unqualified user-defined types are resolved against known enums (see D-012).
6. Unnamed constraints and indexes get PostgreSQL's generated names (`t_pkey`, `t_col_key`,
   `t_col_fkey`, `t_col_check`, `t_col_idx`) so the diff can match them. Verified against a
   real server in phase 4.
7. Tests: one per statement kind and per error, plus "a dump and the same schema written by
   hand build equal catalogs".

8. Sequence defaults must be resolved here: a real dump prints `NO MAXVALUE` where the source
   said `MAXVALUE -1` on a descending sequence, because -1 is the default (see `RealDumpTest`).
   Ascending: min 1, max of the type, start = min. Descending: max -1, min of the type, start = max.

**1.6 Golden tests (started).** `engine/src/test/resources/golden/shop/` holds a hand-written
schema and the same schema dumped by a real PostgreSQL 16.15; `RealDumpTest` parses both. Still to
do once the catalog exists: assert that `source.sql` and `pg_dump_16.sql` build **equal catalogs**.
That is the phase 1 exit test. Add a second fixture with a different style of schema.

**Local PostgreSQL.** The Claude build container has PostgreSQL 16 server binaries in
`/usr/lib/postgresql/16/bin` (run them as the `postgres` user, for example with
`runuser -u postgres -- ...`; a work directory under `/var/lib/postgresql/` works). The exact
recipe is in `engine/src/test/resources/golden/README.md`. Use it to check any doubt about
PostgreSQL behaviour instead of relying on memory. Docker is not available there.

## Done

- [x] **Phase 0** Blueprint (2026-11-12)
- [x] **1.1** Scaffold: Maven multi-module build, Maven wrapper, GitHub Actions CI, coverage gate, docs (2026-11-12)
- [x] **1.2** Lexer (2026-11-12)
- [x] **1.3** Schema model: `QualifiedName`, `DataType`, `Column`, sealed `Constraint` family, `Table`, `Expression` (2026-11-12)
- [x] **1.4a** Parser: statement splitter, token cursor, type parser, expression renderer, full `CREATE TABLE` (2026-11-12)
- [x] **1.4b** Parser: `CREATE TYPE ... AS ENUM`, `ALTER TYPE` add/rename value, `CREATE SEQUENCE`, `ALTER SEQUENCE ... OWNED BY`, `CREATE INDEX`, `ALTER TABLE` (17 actions), `DROP`; parser split into one class per statement family (2026-11-13)
- [x] **1.6 (part)** First golden fixture: a real `pg_dump` from PostgreSQL 16.15 parses with no errors and only the four expected "unsupported" warnings (view, function, trigger, partition attach) (2026-11-13)

## Phases

| Phase | Deliverable | Status |
| --- | --- | --- |
| 0 | Blueprint | Done |
| 1 | Scaffold, CI, lexer, parser, schema model | In progress (1.1 to 1.4b done; 1.5 catalog and 1.6 golden tests left) |
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
- `ALTER TABLE`: one action the parser does not model (`SET STATISTICS`, `ENABLE ROW LEVEL
  SECURITY`, `ATTACH PARTITION`, ...) makes the whole statement "unsupported" (see D-019).
- `ALTER TABLE ... ADD CONSTRAINT name NOT NULL col` becomes `SetNotNull` and loses the name.
- `ALTER TABLE` accepts `RENAME` mixed with other actions; PostgreSQL requires it alone.
- Any `ALTER ... OWNER TO role` statement is ignored as a whole, by its last three tokens.
