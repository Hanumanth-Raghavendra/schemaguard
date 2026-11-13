# Architecture decisions

One entry per decision, newest last. A decision is changed only by adding a new entry that supersedes it.

| ID | Date | Decision | Why |
| --- | --- | --- | --- |
| D-001 | 2026-11-12 | PostgreSQL DDL only for 1.0 | Dialects differ too much to do several well; Postgres lock behaviour is the best documented |
| D-002 | 2026-11-12 | One repository, Maven multi-module: `engine`, later `api`, `cli`; `web` and `action` alongside | The engine is reused by three products and must stay free of framework code |
| D-003 | 2026-11-12 | `engine` has zero runtime dependencies | Testable without Spring, tiny CLI jar, nothing to break on upgrade |
| D-004 | 2026-11-12 | Hand-written lexer and recursive-descent parser instead of a parsing library | Full control over `ALTER` coverage and error positions; unsupported input is reported, not guessed at |
| D-005 | 2026-11-12 | The lexer emits `WORD` and does not classify keywords | Most PostgreSQL keywords are non-reserved, so only the parser has the context to decide |
| D-006 | 2026-11-12 | The engine never throws on bad SQL; it returns diagnostics with source spans | One error must not hide the rest of a large dump; the UI needs line and column |
| D-007 | 2026-11-12 | No license file yet (all rights reserved by default) | Open source vs source-available affects the income path; decide before 1.0 |
| D-008 | 2026-11-12 | Java 21, JUnit 6.1.3, AssertJ 3.27.7, JaCoCo 0.8.16; line-coverage gate of 85% on `engine` | Versions checked against Maven Central on this date; the gate keeps the engine honest |
| D-009 | 2026-11-12 | Base package `io.github.hanumanthraghavendra.schemaguard`, groupId `io.github.hanumanth-raghavendra` | The reverse-domain namespace the author actually controls, valid for Maven Central later |
| D-010 | 2026-11-12 | The parser returns a list of `Statement`s; a separate `CatalogBuilder` (1.5) turns them into a schema | Rules need the statements themselves ("this ALTER rewrites the table"); diff needs the resulting schema. One parser serves both |
| D-011 | 2026-11-12 | Expressions are stored as canonical text, not as a syntax tree | The engine only compares and prints expressions. A full expression grammar would be most of the SQL language for no benefit to version 1.0 |
| D-012 | 2026-11-12 | Type names are canonicalised at parse time (`int4` -> `integer`, `varchar` -> `character varying`, `char` -> `character(1)`); unqualified user types keep a null schema until the catalog resolves them | Stops alias spellings showing up as false differences; the parser alone cannot know whether `status_t` is built in |
| D-013 | 2026-11-12 | Column-level constraints are lifted into table-level constraints over one column; primary-key and identity columns are marked NOT NULL | One shape for later stages, and it matches what PostgreSQL stores in its catalog |
| D-014 | 2026-11-12 | Parser errors use an internal exception caught once per statement; the public API never throws | Keeps the recursive-descent methods free of error plumbing while preserving D-006 |
| D-015 | 2026-11-12 | Statements are split on every semicolon, except inside `BEGIN ATOMIC ... END` and `CREATE RULE ... DO (...)` | Splitting on parenthesis depth would let one missing `)` swallow the rest of the file |
| D-016 | 2026-11-13 | The parser is split into `ParserBase` (cursor + shared grammar fragments), `TableElementParser` (columns and constraints) and one class per statement family (`IndexParser`, `SequenceParser`, `AlterTableParser`), all sharing one `TokenCursor` | Each file holds one grammar. `ALTER TABLE ... ADD` reuses the exact column and constraint code of `CREATE TABLE`, so the two cannot drift apart |
| D-017 | 2026-11-13 | `ALTER TABLE` is kept as a list of sealed `AlterAction` records; the parser does not apply them | The action is what carries the risk (lock level, table rewrite). The catalog (1.5) applies actions; the rules engine (phase 3) judges them |
| D-018 | 2026-11-13 | Index keys always store sort order and NULLs order with defaults filled in; sequences leave unspecified start, min and max empty | Index defaults are fixed, so filling them in makes equal indexes compare equal. Sequence defaults depend on type and direction, so the catalog resolves them |
| D-019 | 2026-11-13 | An `ALTER TABLE` containing any unmodelled action is reported as unsupported as a whole | A half-understood statement would produce a falsely reassuring risk report |
