# SchemaGuard

[![CI](https://github.com/Hanumanth-Raghavendra/schemaguard/actions/workflows/ci.yml/badge.svg)](https://github.com/Hanumanth-Raghavendra/schemaguard/actions/workflows/ci.yml)

A PostgreSQL migration-safety platform. Give it two versions of a schema and it tells you
what changed, what that change will lock or break in production, and how to ship it safely.

> **Status: early development.** The engine reads PostgreSQL schemas (including ones built up by migrations) and reports every difference between two of them, with rename detection. Both are checked against a real PostgreSQL 16. Next: risk rules.
> See [PROGRESS.md](PROGRESS.md) for the current state and roadmap.

## Why

A single `ALTER TABLE` can take a lock that blocks every write to a busy table. Most teams find
out in production. SchemaGuard checks the migration before it runs:

1. **Diff**: a structured comparison of two schemas, including renames.
2. **Risk**: each change is checked against rules for locks, table rewrites, data loss and
   application compatibility, then scored.
3. **Plan**: an ordered, safe migration, using `CREATE INDEX CONCURRENTLY`,
   `NOT VALID` followed by `VALIDATE CONSTRAINT`, and `lock_timeout` guards.

## Planned shape

| Module | Role | Status |
| --- | --- | --- |
| `engine` | Lexer, parser, schema model, diff, rules, planner. Pure Java, no dependencies | In progress |
| `api` | Spring Boot REST service | Planned |
| `cli` | Command-line tool built on the engine | Planned |
| `web` | React + TypeScript front end | Planned |
| `action` | GitHub Action that reviews migrations on pull requests | Planned |

## Build

Requires JDK 21 or newer. Maven is downloaded by the wrapper.

```bash
./mvnw -B -ntp verify        # Linux, macOS, Git Bash
mvnw.cmd -B -ntp verify      # Windows Command Prompt or PowerShell
```

This compiles the code, runs the tests and enforces the coverage gate.

## Documentation

- [PROGRESS.md](PROGRESS.md): current state and next step
- [docs/DECISIONS.md](docs/DECISIONS.md): architecture decisions and their reasons
- [docs/INTERVIEW.md](docs/INTERVIEW.md): how each part works and why
