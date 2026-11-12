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
