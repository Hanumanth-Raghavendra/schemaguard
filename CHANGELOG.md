# Changelog

All notable changes, newest first. Versions follow [Semantic Versioning](https://semver.org/);
each release is a git tag (`v0.5.0`) on `main`. Details and reasons: `PROGRESS.md` and
`docs/DECISIONS.md`.

## Unreleased (0.6.0)

- **CLI**: the engine on the command line, offline, as one runnable jar (`cli/`).
  `schemaguard diff|report|plan|lint` print text, Markdown (the web app's report layout) or JSON
  (the API's response bodies); `--fail-above` sets the exit code for CI; `lint --migrations` checks
  a folder of migrations, each against the schema the ones before it build. `schemaguard save`
  keeps a schema as a project version with an API key.
- **API keys** for scripts and CI: make one on the account page (its value is shown once), send it
  as `Authorization: Bearer sg_...`, and it may list projects and save versions, nothing else. The
  list shows when each key was last used; delete one and it stops working at once. API:
  `/api/v1/account/keys`.
- **Risk trend and reports**: a project shows a bar per version, its height the risk of that
  change and its colour the level, each bar opening its version. A version's report (risks,
  changes, safe plan) downloads as Markdown or JSON or is shared as a link; its SQL downloads too.
- The "None" risk tag has its border again.
- **Projects**: keep every version of a schema in a named project and see how risky each change
  was. Save a version on the project page (starting from the latest one) or straight from the
  analyser; open any version's change in the analyser. A version that changes nothing is refused.
  API: `/api/v1/projects` and `/api/v1/projects/{id}/versions`.
- **Accounts**: sign up, log in, stay logged in across visits, log out, delete the account
  (`/signup`, `/login`, `/account`; API `POST /api/v1/auth/signup|login|refresh|logout`,
  `GET`/`DELETE /api/v1/account`). Short-lived JWT access tokens (RFC 9068) kept in memory; the
  refresh token is an httpOnly, SameSite=Strict cookie that rotates on every use, and a reused one
  ends the session. Passwords are hashed with Argon2id and checked against the 10,000 most common;
  repeated wrong passwords lock the account for a minute, then longer. Analysing and sharing still
  need no account.
- The web app reaches the API through its own address (Vercel forwards `/api`).
- The API's `/actuator/info` reports the real build time again; Maven 3.10's fixed
  reproducible-build timestamp made it read 1980-02-01.

## 0.5.0 (2026-11-20): the web app

Live at https://schemaguard-v1.vercel.app.

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
