# SchemaGuard: progress

> **To resume:** read this file, then start at "Next step". Do not re-plan; the plan is fixed below.

- **Current phase:** 2 (diff engine). Phase 1 is complete.
- **Last updated:** 2026-11-13
- **Build command (repo root):** `./mvnw -B -ntp verify` (Windows: `mvnw.cmd -B -ntp verify`)
- **State:** build green, 507 tests, engine line coverage 99.5%, branch 95.4% (gate: 85% line)
- **Size:** 92 files in 23 folders; 5746 lines of main Java, 3646 lines of test Java (Trackly reference: 249 files, 5,888 Java + 11,781 JS lines)

## Next step

**Phase 2: the diff engine.** New package `engine/src/main/java/.../engine/diff/`.

1. `SchemaDiff.compare(Catalog before, Catalog after)` returns an ordered `List<Change>`.
   `Change` is a sealed interface of records, one per kind of difference:
   tables (added, dropped), columns (added, dropped, type, nullability, default, identity,
   generated expression, collation), constraints (added, dropped, definition changed, validated),
   indexes (added, dropped, definition changed), enums (added, dropped, label added, label
   renamed, labels reordered or removed), sequences (added, dropped, settings changed).
2. **Match by name first, then by definition.** A constraint or index with a new name and an
   unchanged definition is a rename, not a drop plus an add.
3. **Expressions.** Equal text: unchanged. Different text with equal `Expressions.fingerprint`:
   report as changed with `cosmetic = true` ("differs only in parentheses or casts; probably
   how PostgreSQL printed it"). Otherwise a real change. Never silently drop a difference.
4. **Rename candidates.** In one table, a dropped column and an added column with the same
   type and attributes are a probable rename. Score by name similarity (edit distance) and
   position; report `RenameCandidate` with the score. Same idea for tables with equal columns.
   The diff reports both readings; it does not decide.
5. Changes come out in a fixed order (by object name, then kind) so output is reproducible.
6. Tests: one per change kind; `compare(c, c)` is empty for every golden fixture; for each
   fixture, `compare(catalog(source.sql), catalog(pg_dump_16.sql))` contains only cosmetic
   expression changes. Add fixtures `golden/<name>/before.sql` + `after.sql` for realistic diffs.

**Local PostgreSQL.** The Claude build container has PostgreSQL 16 server binaries in
`/usr/lib/postgresql/16/bin`. Run them as the `postgres` user (`runuser -u postgres -- ...`)
with a work directory under `/var/lib/postgresql/`. Start one, then use
`scripts/regenerate-golden.sh` (see `engine/src/test/resources/golden/README.md`).
**Settle every doubt about PostgreSQL behaviour by asking the server, not from memory.** In
phase 1 this caught four wrong assumptions before they shipped. Docker is not available there.

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

## Phases

| Phase | Deliverable | Status |
| --- | --- | --- |
| 0 | Blueprint | Done |
| 1 | Scaffold, CI, lexer, parser, schema model, catalog | Done (2026-11-13) |
| 2 | Diff engine with rename detection | Next |
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
- **Not read yet:** views, functions, triggers, policies, `CREATE TABLE ... PARTITION OF` and
  `ATTACH PARTITION`, `EXCLUDE` constraints, typed tables, `LIKE`. Each is reported by name.
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
