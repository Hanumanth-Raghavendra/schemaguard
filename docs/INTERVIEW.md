# Interview notes

What was built in each phase, why it was built that way, and the questions it invites.
Read the code next to these notes; you should be able to explain every line.

## Phase 1.2: the lexer

**What it is.** `Lexer.tokenize(sql)` turns SQL text into a list of tokens (`WORD`, `STRING`,
`NUMBER`, `OPERATOR`, punctuation) plus a list of diagnostics. It is a single pass over the
characters with one character of lookahead (two in a few places), so it runs in O(n).

**Design choices worth explaining**

1. **No keyword table.** In PostgreSQL most keywords are non-reserved: `name`, `type`, `status`
   are legal column names. Only context tells you which is which, so the lexer emits `WORD`
   and the parser decides. A lexer with a keyword list would mis-tokenize real schemas.
2. **Errors are data, not exceptions.** Every problem becomes a `Diagnostic` with a code
   (`SG-LEX-001`...), a message and a `SourceSpan` (offset, line, column). Lexing continues, so
   one unterminated string in a 5,000-line dump still yields every other finding.
3. **Case folding copies the server.** Unquoted identifiers fold to lower case, ASCII letters
   only, which is what PostgreSQL does in a UTF-8 database. `"Quoted"` identifiers keep their case.
   So `Users` and `users` are the same table, and `"Users"` is a different one.
4. **The operator rule.** A multi-character operator cannot end in `+` or `-` unless it contains
   one of ``~ ! @ # % ^ & | ` ?``. That is why `a=-1` means `a = -1`. The lexer takes the longest
   run of operator characters and then backs up (`rewind`) to honour the rule.
5. **Dollar quoting.** `$$ ... $$` and `$tag$ ... $tag$` bodies are taken verbatim. Without this,
   every `;` inside a function body would split the statement in the wrong place.
6. **Nested comments.** `/* a /* b */ c */` is one comment in PostgreSQL (not in C or Java), so
   the lexer keeps a depth counter.
7. **pg_dump awareness.** Lines such as `\connect` and `\restrict` are psql commands, and the
   rows after `COPY ... FROM stdin;` are data. A tiny state machine (`trackStatement`) spots
   the COPY form and skips to the `\.` terminator; each is emitted as one token for the parser to ignore.

**Questions you should be able to answer**

- Why write a lexer by hand instead of using a parser generator or a library? (D-004: control over
  coverage and error messages, zero dependencies, and SQL dialect libraries have gaps in `ALTER`.)
- What is the difference between a lexer and a parser? (Characters to tokens vs tokens to structure.)
- How do you know the lexer is correct? (67 tests, 99.7% line coverage, and a realistic pg_dump
  fixture that must lex with zero diagnostics. From phase 4, generated SQL is run on a real Postgres.)
- What does it not handle? (`U&` Unicode escapes, and string constants continued across lines.
  Both are reported or documented, not silently mis-read.)
- Why records? (Immutable value types with generated `equals`/`hashCode`; tokens never change after creation.)

## Phase 1.3 and 1.4a: the schema model and the CREATE TABLE parser

**What it is.** `Parser.parse(sql)` returns one `Statement` per SQL statement plus diagnostics.
`CREATE TABLE` becomes a `Table` made of immutable records (`Column`, `DataType`, and a sealed
`Constraint` family: `PrimaryKey`, `Unique`, `ForeignKey`, `Check`).

**How recursive descent works.** Each grammar rule is a method, and methods call each other the
way the grammar nests: `parseCreateTable` -> `parseTableElement` -> `parseColumn` ->
`TypeParser.parse` / `referencesClause` / `defaultExpression`. `TokenCursor` gives the lookahead:
`acceptWord("unique")` consumes a keyword if it is next, `acceptWords("primary", "key")` consumes
several only if all are present. There is no backtracking, so parsing is O(n) in the token count.

**Design choices worth explaining**

1. **Sealed interfaces + records + pattern-matching `switch`.** `Constraint` is sealed, so the
   compiler knows every subtype. A `switch` over a constraint needs no `default`, and adding a
   fifth constraint kind makes every such switch fail to compile until it is handled.
2. **Canonical types (D-012).** `int`, `int4` and `integer` all become `integer`; `char` becomes
   `character(1)`; `decimal(10)` becomes `numeric(10,0)`. Without this the diff engine would
   report differences that do not exist.
3. **Where a DEFAULT ends.** PostgreSQL's grammar restricts a default to a "b_expr", which cannot
   contain a bare `NOT`. So in `DEFAULT now() NOT NULL` the default is `now()`. The parser reads
   until a top-level keyword that starts another column constraint.
4. **Expressions as canonical text (D-011).** `TOTAL>=0` and `(( total >= 0 ))` both render as
   `total >= 0`. The renderer normalises case, spacing, quoting and redundant outer parentheses,
   and tells a unary minus (`-1`) from a binary one (`a - 1`) by looking at the previous token.
5. **Errors as control flow, contained (D-014).** A syntax error throws a private `ParseError`
   that is caught once per statement and turned into a diagnostic with a line and column. One
   bad statement never affects the next.
6. **Nothing is dropped silently.** Every statement becomes `CreateTable`, `Ignored` (known and
   irrelevant, such as `SET`), `Unsupported` (with a warning) or `Invalid` (with an error).
7. **The same checks the server makes.** Duplicate columns, two primary keys, and a key over a
   column that does not exist are all reported at parse time.

**Questions you should be able to answer**

- What is recursive descent, and what are its limits? (One method per rule, top-down. It cannot
  handle left-recursive grammars directly, which is why expressions are not parsed into a tree here.)
- Why records and sealed types instead of classes with setters? (Immutability: a parsed table can
  be shared and compared safely; `equals` comes for free, which the diff engine relies on.)
- How do you test a parser? (222 tests: a table of 50 type spellings, 31 syntax errors with exact
  messages, and a test that cuts a valid script at every character position and asserts the
  parser never throws.)
- Why not use an existing SQL parser library? (D-004.)
- What happens with SQL the parser does not understand? (A warning with the statement kind and
  position; the rest of the file is still analysed.)
