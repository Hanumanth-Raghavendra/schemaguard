# Changelog

All notable changes, newest first. Versions follow [Semantic Versioning](https://semver.org/);
each release is a git tag (`v0.5.0`) on `main`. Details and reasons: `PROGRESS.md` and
`docs/DECISIONS.md`.

## 0.5.0 (2026-11-20): the web app

Live at https://schemaguard-nu.vercel.app.

- **Compare two schemas**: two code editors with PostgreSQL highlighting; Risks (worst first,
  each with why it matters and what to do instead), Changes (added, removed, changed, possible
  rename), Diagram and Plan tabs. Every lock is drawn on PostgreSQL's eight-step lock scale.
- **Plan**: safe or direct route, rename confirmation that re-plans, copy the script, and check
  the plan itself as a migration.
- **Check a migration**: a hand-written script judged statement by statement, with failures
  under the statement they belong to.
- **ER diagram** of both schemas with every table, column and foreign key marked; pan and zoom
  by mouse or keyboard. New endpoint `POST /api/v1/model`.
- **Example gallery**: seven real-world changes, one click each.
- **Share links** (`/a/{id}`, kept 30 days, deletable by the sharer) and **export** as Markdown
  or JSON.
- Exact OpenAPI contract: every response field required, nullable ones marked; the web app's
  TypeScript types are generated from it and CI fails on drift.
- Quality: end-to-end browser test in CI and against the live site; Content-Security-Policy and
  other security headers; long-lived caching for hashed assets; a failed download of the editor
  or diagram no longer blanks the page.
- Engine: an unquoted reserved word is no longer accepted as a constraint name
  (`CONSTRAINT both`), matching PostgreSQL.

## 0.1.0 (2026-11-18): engine and REST API

Live at https://schemaguard-api-c00y.onrender.com.

- Engine (pure Java 21): PostgreSQL DDL lexer and parser, schema catalog, diff with rename
  detection, 40 rules with lock facts measured on PostgreSQL 16, a 0 to 100 risk score, a
  migration planner (direct and safe routes) proven on a real PostgreSQL, and a migration linter.
- REST API (Spring Boot 4): diff, report, plan, lint and rules; RFC 9457 errors; OpenAPI and
  Swagger UI; optional PostgreSQL storage for shareable saved analyses (Neon).
- Docker image, Render deploy that waits for CI, live smoke test.
