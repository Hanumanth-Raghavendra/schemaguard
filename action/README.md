# SchemaGuard GitHub Action

Checks the PostgreSQL migrations a pull request adds, posts the risk report as a comment, and fails
the check when the risk is above a limit. It runs the [SchemaGuard CLI](../README.md#use-the-cli)
offline on the runner; nothing is sent anywhere unless you ask it to save a schema.

```yaml
# .github/workflows/schemaguard.yml
name: SchemaGuard
on: pull_request

permissions:
  contents: read
  pull-requests: write   # for the comment

jobs:
  migrations:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: Hanumanth-Raghavendra/schemaguard/action@v0.6.0
        with:
          migrations: src/main/resources/db/migration
          fail-above: medium
```

Each migration the pull request adds or changes is checked against the schema the files before it
build, so a new `V7__orders.sql` is judged against everything already deployed. The comment is
updated on every push rather than repeated, and lines PostgreSQL would refuse are annotated in the
diff.

## Inputs

| Input | Default | Meaning |
| --- | --- | --- |
| `migrations` | | A folder of migrations, relative to the repository root. Flyway's `V<version>__` files run in version order (`R__` and `U` files are not checked); other names in name order |
| `schema` | | Instead of `migrations`: a schema file (`pg_dump --schema-only`) kept in the repository. On a pull request, the base branch's version is compared with the pull request's |
| `files` | `changed` | `changed`: on a pull request, the migrations it adds or changes (otherwise all). `all`: every migration |
| `fail-above` | `high` | Fail when the risk is above a score (0 to 100) or a level: `none`, `low`, `medium`, `high`. A statement PostgreSQL would refuse fails too |
| `comment` | `true` | Post the report as a pull-request comment |
| `cli-version` | the action's tag | The CLI release to download, such as `0.6.0` |
| `cli-jar` | | A CLI jar already on the runner, used instead of downloading one |
| `api-key`, `project` | | With `schema`: on a push to the default branch, save the schema as the project's next version on the SchemaGuard site. Keep the key in a secret |
| `github-token` | `github.token` | The token used for the comment |

## Outputs

`score` (0 to 100), `level` (`NONE` to `CRITICAL`), `exit-code` (0 within the limit, 1 above it) and
`report` (the path of the Markdown report). `score` and `level` are empty when nothing needed
checking.

## Good to know

- **Java.** The action uses a Java 21 already on the runner (GitHub's hosted runners have one) and
  never changes the job's `JAVA_HOME`. Elsewhere, add `actions/setup-java` with Java 21 first.
- **The jar.** Pin the action to a release tag: it downloads that release's
  `schemaguard-cli-<version>.jar` and checks it against the published SHA-256. Releases carry the
  jar from v0.6.0 on; before that, build the CLI in the job and pass `cli-jar`.
- **Forks.** A pull request from a fork gets a read-only token, so the action cannot comment there;
  the report still goes to the job summary and the check still passes or fails.
- **Shallow checkouts are fine.** The action fetches the one base commit it needs.
- **Runners.** Linux and macOS runners (the action is a Bash script).
