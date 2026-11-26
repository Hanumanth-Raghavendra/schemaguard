#!/usr/bin/env bash
# Tests the action's script: each case builds a throwaway git repository with a base commit and a
# change on top, runs the script with the real CLI jar, and stands in for gh and curl with scripts
# that record every call. Usage: action/test/test.sh <schemaguard-cli jar>
#
# Each check's conditions are strings that all() evaluates after the run, so their $ stays unexpanded.
# shellcheck disable=SC2016
set -euo pipefail

[[ $# -eq 1 && -f $1 ]] || { echo "usage: $0 <schemaguard-cli jar>" >&2; exit 2; }
here=$(cd "$(dirname "$0")" && pwd)
script=$here/../schemaguard.sh
jar=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
java_home=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")
unset JAVA_TOOL_OPTIONS GITHUB_OUTPUT GITHUB_STEP_SUMMARY
tests=0
failures=0

# ------------------------------------------------------------------ scratch repositories

# A repository whose base commit has two migrations and a schema; $base names that commit.
fresh() {
  t=$(mktemp -d)
  repo=$t/repo
  mkdir -p "$repo/db/migration" "$t/bin" "$t/log"
  cat > "$t/bin/gh" << 'EOF'
#!/usr/bin/env bash
echo "gh $*" >> "$FAKE_LOG/gh.log"
for arg in "$@"; do [[ $arg == body=@* ]] && cp "${arg#body=@}" "$FAKE_LOG/body.md"; done
if [[ " $* " == *" -X PATCH "* || " $* " == *" -X POST "* ]]; then
  if [[ ${FAKE_GH_FAIL:-} == 1 ]]; then echo "HTTP 403: Resource not accessible by integration" >&2; exit 1; fi
  exit 0
fi
printf '%s' "${FAKE_COMMENTS:-[]}"
EOF
  cat > "$t/bin/curl" << 'EOF'
#!/usr/bin/env bash
out=""
url=""
while [[ $# -gt 0 ]]; do
  case $1 in -o) out=$2; shift 2 ;; -*) shift ;; *) url=$1; shift ;; esac
done
echo "$url" >> "$FAKE_LOG/curl.log"
case $url in
  *.sha256)
    sum=$(sha256sum "$FAKE_JAR" | cut -d' ' -f1)
    [[ ${FAKE_BAD_SUM:-} == 1 ]] && sum=0000000000000000000000000000000000000000000000000000000000000000
    name=${url##*/}
    echo "$sum  ${name%.sha256}" > "$out" ;;
  *) cp "$FAKE_JAR" "$out" ;;
esac
EOF
  chmod +x "$t/bin/gh" "$t/bin/curl"
  cd "$repo"
  git init -q -b main
  git config user.email test@example.com
  git config user.name Test
  echo "CREATE TABLE customers (id bigint PRIMARY KEY, email text NOT NULL);" > db/migration/V1__customers.sql
  printf "SET lock_timeout = '5s';\nALTER TABLE customers ADD COLUMN name text;\n" > db/migration/V2__name.sql
  echo "CREATE TABLE customers (id bigint PRIMARY KEY, email text NOT NULL);" > schema.sql
  echo "# app" > README.md
  git add -A
  git commit -q -m base
  base=$(git rev-parse HEAD)
}

# Commits files on top of the base: change <path> <content> [<path> <content>...].
change() {
  while [[ $# -gt 0 ]]; do
    mkdir -p "$(dirname "$1")"
    printf '%s\n' "$2" > "$1"
    shift 2
  done
  git add -A
  git commit -q -m change
}

risky_migration() {
  change db/migration/V3__email_index.sql "CREATE INDEX customers_email ON customers (email);"
}

# Runs the script with these extra variables; sets $code; output in $t/out.
run() {
  code=0
  (cd "$repo" && env PATH="$t/bin:$PATH" RUNNER_TEMP="$t" GITHUB_OUTPUT="$t/output" GITHUB_STEP_SUMMARY="$t/summary" \
    FAKE_LOG="$t/log" FAKE_JAR="$jar" SG_CLI_JAR="$jar" "$@" "$script") > "$t/out" 2>&1 || code=$?
}

pull_request() {
  run SG_EVENT=pull_request SG_BASE_SHA="$base" SG_PR=7 SG_REPO=owner/app "$@"
}

# ------------------------------------------------------------------ assertions

check() {
  local name=$1
  shift
  tests=$((tests + 1))
  if "$@"; then
    echo "ok   $name"
  else
    failures=$((failures + 1))
    echo "FAIL $name (exit code $code)"
    sed 's/^/     | /' "$t/out"
    [[ -f $t/log/gh.log ]] && sed 's/^/     gh | /' "$t/log/gh.log"
  fi
}

has() { grep -qF -- "$2" "$1" 2> /dev/null; }
lacks() { ! grep -qF -- "$2" "$1" 2> /dev/null; }
exits() { [[ $code -eq $1 ]]; }
all() { local condition; for condition in "$@"; do eval "$condition" || return 1; done; }

# ------------------------------------------------------------------ pull requests: migrations

fresh
risky_migration
pull_request SG_MIGRATIONS=db/migration SG_FAIL_ABOVE=medium
check "a pull request's new migration is checked, fails above the limit and is commented on" all \
  'exits 1' \
  'has "$t/log/gh.log" "gh api --paginate repos/owner/app/issues/7/comments?per_page=100"' \
  'has "$t/log/gh.log" "-X POST repos/owner/app/issues/7/comments"' \
  '[[ $(head -n 1 "$t/log/body.md") == "<!-- schemaguard: db/migration -->" ]]' \
  'has "$t/log/body.md" "**SchemaGuard failed:** the risk, 60/100 (High), is above --fail-above medium."' \
  'has "$t/log/body.md" "## V3__email_index.sql"' \
  'lacks "$t/log/body.md" "## V1__customers.sql"' \
  'has "$t/log/body.md" "SchemaGuard 0."' \
  'has "$t/output" "score=60"' \
  'has "$t/output" "level=HIGH"' \
  'has "$t/output" "exit-code=1"' \
  'has "$t/summary" "## V3__email_index.sql"'

fresh
risky_migration
pull_request SG_MIGRATIONS=./db/migration/ SG_FAIL_ABOVE=high
check "within the limit it passes; ./ and a trailing / are fine" all \
  'exits 0' \
  'has "$t/log/body.md" "**SchemaGuard passed:** the risk, 60/100, is within \`fail-above: high\`."' \
  'has "$t/log/body.md" "<!-- schemaguard: db/migration -->"'

fresh
risky_migration
pull_request SG_MIGRATIONS=db/migration \
  FAKE_COMMENTS='[{"id": 41, "body": "Looks good"}][{"id": 42, "body": "<!-- schemaguard: db/migration -->\nold"}]'
check "the earlier comment, on any page, is updated instead of adding another" all \
  'exits 0' \
  'has "$t/log/gh.log" "-X PATCH repos/owner/app/issues/comments/42"' \
  'lacks "$t/log/gh.log" "-X POST"'

fresh
change README.md "# app, renamed"
pull_request SG_MIGRATIONS=db/migration
check "a pull request that changes no migration is checked by nobody and gets no comment" all \
  'exits 0' \
  'has "$t/out" "No migrations in \`db/migration\` changed in this pull request."' \
  'lacks "$t/log/gh.log" "-X "' \
  'has "$t/output" "score="' \
  'has "$t/output" "exit-code=0"'

fresh
change README.md "# app, renamed"
pull_request SG_MIGRATIONS=db/migration FAKE_COMMENTS='[{"id": 9, "body": "<!-- schemaguard: db/migration -->\nold report"}]'
check "...but an earlier comment is corrected" all \
  'exits 0' \
  'has "$t/log/gh.log" "-X PATCH repos/owner/app/issues/comments/9"' \
  'has "$t/log/body.md" "No migrations in \`db/migration\` changed"'

fresh
change db/migration/R__views.sql "CREATE VIEW v AS SELECT 1;" db/migration/U2__undo.sql "ALTER TABLE customers DROP COLUMN name;" \
  db/migration/archive/V0__old.sql "CREATE TABLE old (id int);"
pull_request SG_MIGRATIONS=db/migration
check "repeatable and undo files, and files in subfolders, are not checked" all \
  'exits 0' \
  'has "$t/out" "No migrations in \`db/migration\` changed"'

fresh
risky_migration
change db/migration/V4__broken.sql "ALTER TABLE nowhere ADD COLUMN a text;"
pull_request SG_MIGRATIONS=db/migration SG_FAIL_ABOVE=high
check "diagnostics become annotations on the file's line, and a refused statement fails" all \
  'exits 1' \
  'has "$t/out" "::error file=db/migration/V4__broken.sql,line=1,col=1::"' \
  'has "$t/log/body.md" "**SchemaGuard failed:** PostgreSQL would refuse statements in V4__broken.sql (1)."' \
  'has "$t/log/body.md" "## V3__email_index.sql"' \
  'has "$t/log/body.md" "## V4__broken.sql"'

fresh
risky_migration
pull_request SG_MIGRATIONS=db/migration SG_FILES=all
check "files: all checks every migration on a pull request too" all \
  'exits 0' \
  'has "$t/summary" "## V1__customers.sql"' \
  'has "$t/summary" "## V3__email_index.sql"'

fresh
risky_migration
pull_request SG_MIGRATIONS=db/migration SG_COMMENT=false
check "comment: false only writes the job summary" all \
  'exits 0' \
  '[[ ! -f $t/log/gh.log ]]' \
  'has "$t/summary" "## V3__email_index.sql"'

fresh
risky_migration
pull_request SG_MIGRATIONS=db/migration SG_FAIL_ABOVE=medium FAKE_GH_FAIL=1
check "a comment the token may not write is a warning, not a failure of its own" all \
  'exits 1' \
  'has "$t/out" "::warning title=SchemaGuard::Could not comment on the pull request"' \
  'has "$t/summary" "## V3__email_index.sql"'

fresh
long=""
for i in $(seq 1 400); do long+="CREATE INDEX i$i ON customers (email);"$'\n'; done
change db/migration/V3__many.sql "$long"
pull_request SG_MIGRATIONS=db/migration SG_FAIL_ABOVE=100
check "a report too long for a comment is cut there and kept whole in the summary" all \
  'exits 0' \
  '[[ $(wc -c < "$t/log/body.md") -lt 61000 ]]' \
  'has "$t/log/body.md" "The report is too long for a comment"' \
  '[[ $(wc -c < "$t/summary") -gt 61000 ]]'

# ------------------------------------------------------------------ pushes

fresh
risky_migration
run SG_EVENT=push SG_PUSH_BEFORE="$base" SG_MIGRATIONS=db/migration SG_FAIL_ABOVE=medium
check "a push checks every migration and comments nowhere" all \
  'exits 1' \
  '[[ ! -f $t/log/gh.log ]]' \
  'has "$t/summary" "## V1__customers.sql"' \
  'has "$t/summary" "Checked 3 migrations; the riskiest scores 60/100 (High)."'

# ------------------------------------------------------------------ a schema file

fresh
change schema.sql "CREATE TABLE customers (id bigint PRIMARY KEY, email text NOT NULL);
CREATE INDEX customers_email ON customers (email);"
pull_request SG_SCHEMA=schema.sql SG_FAIL_ABOVE=medium
check "a schema file is compared with the base branch's version" all \
  'exits 1' \
  'has "$t/log/body.md" "<!-- schemaguard: schema.sql -->"' \
  'has "$t/log/body.md" "# SchemaGuard risk report"' \
  'has "$t/log/body.md" "Index is built without CONCURRENTLY (SG-R101)"'

fresh
change README.md "# app, renamed"
pull_request SG_SCHEMA=schema.sql
check "an unchanged schema is not checked" all 'exits 0' 'has "$t/out" "\`schema.sql\` did not change."'

fresh
change db/new.sql "CREATE TABLE t (id bigint PRIMARY KEY);"
pull_request SG_SCHEMA=db/new.sql
check "a schema file new in the pull request is compared with an empty schema" all \
  'exits 0' 'has "$t/log/body.md" "+ table public.t"'

fresh
change schema.sql "CREATE TABLE customers (id bigint PRIMARY KEY, email text NOT NULL, name text);"
run SG_EVENT=push SG_PUSH_BEFORE=0000000000000000000000000000000000000000 SG_SCHEMA=schema.sql
check "a push that starts a branch has nothing to compare with" all \
  'exits 0' 'has "$t/out" "There is no earlier version of \`schema.sql\` to compare with."'

fresh
change schema.sql "CREATE TABLE customers (id bigint PRIMARY KEY, email text NOT NULL, name text);"
run SG_EVENT=push SG_PUSH_BEFORE="$base" SG_REF=refs/heads/main SG_DEFAULT_BRANCH=main SG_SCHEMA=schema.sql \
  SG_API_KEY=sg_TestKeyTestKeyTestKeyTestKeyTestKeyTestKey1 SG_PROJECT=3f9c2a1e-8b7d-4c6e-9f10-2a3b4c5d6e7f \
  SCHEMAGUARD_API=http://127.0.0.1:9
check "a push to the default branch saves the schema with the key, and a failed save fails" all \
  'exits 2' \
  'has "$t/out" "::add-mask::sg_TestKey"' \
  'has "$t/out" "schemaguard: Cannot connect to http://127.0.0.1:9"' \
  'has "$t/out" "::error title=SchemaGuard::Saving the schema to the SchemaGuard project failed"'

fresh
change schema.sql "CREATE TABLE customers (id bigint PRIMARY KEY, email text NOT NULL, name text);"
run SG_EVENT=push SG_PUSH_BEFORE="$base" SG_REF=refs/heads/feature SG_DEFAULT_BRANCH=main SG_SCHEMA=schema.sql \
  SG_API_KEY=sg_TestKeyTestKeyTestKeyTestKeyTestKeyTestKey1 SG_PROJECT=3f9c2a1e-8b7d-4c6e-9f10-2a3b4c5d6e7f \
  SCHEMAGUARD_API=http://127.0.0.1:9
check "...but not from another branch" all 'exits 0' 'lacks "$t/out" "Cannot connect"'

# ------------------------------------------------------------------ the CLI and Java

fresh
risky_migration
pull_request SG_MIGRATIONS=db/migration SG_CLI_JAR= SG_ACTION_REF=v9.8.7
check "without cli-jar, the jar of the release in the action's tag is downloaded and checked" all \
  'exits 0' \
  'has "$t/log/curl.log" "https://github.com/Hanumanth-Raghavendra/schemaguard/releases/download/v9.8.7/schemaguard-cli-9.8.7.jar"' \
  'has "$t/log/curl.log" "schemaguard-cli-9.8.7.jar.sha256"'

fresh
risky_migration
pull_request SG_MIGRATIONS=db/migration SG_CLI_JAR= SG_ACTION_REF=v9.8.7 SG_CLI_VERSION=1.2.3
check "cli-version names another release" all 'exits 0' 'has "$t/log/curl.log" "/v1.2.3/schemaguard-cli-1.2.3.jar"'

fresh
pull_request SG_MIGRATIONS=db/migration SG_CLI_JAR= SG_ACTION_REF=v9.8.7 FAKE_BAD_SUM=1
check "a jar that does not match its checksum is refused" all \
  'exits 2' 'has "$t/out" "::error title=SchemaGuard::schemaguard-cli-9.8.7.jar does not match its published checksum."'

fresh
pull_request SG_MIGRATIONS=db/migration SG_CLI_JAR= SG_ACTION_REF=main
check "an action used at a branch needs cli-version or cli-jar" all \
  'exits 2' 'has "$t/out" "Use the action at a release tag"'

fresh
pull_request SG_MIGRATIONS=db/migration SG_CLI_JAR= SG_CLI_VERSION='1.0; rm -rf /'
check "cli-version must look like a version" all 'exits 2' 'has "$t/out" "cli-version is not a version"'

fresh
risky_migration
cat > "$t/bin/java" << 'EOF'
#!/usr/bin/env bash
echo "    java.specification.version = 17" >&2
exit 1
EOF
chmod +x "$t/bin/java"
pull_request SG_MIGRATIONS=db/migration JAVA_HOME_21_X64="$java_home"
check "an older java on the PATH is passed over for the runner's Java 21" all 'exits 0' 'has "$t/summary" "## V3__email_index.sql"'
pull_request SG_MIGRATIONS=db/migration JAVA_HOME_21_X64=
check "without any Java 21 it says how to get one" all 'exits 2' 'has "$t/out" "SchemaGuard needs Java 21 or newer"'

# ------------------------------------------------------------------ input mistakes

mistake() {
  local name=$1 expected=$2
  shift 2
  fresh
  pull_request "$@"
  check "$name" all 'exits 2' "has \"\$t/out\" \"::error title=SchemaGuard::$expected\""
}
mistake "both migrations and schema" "Give exactly one of the inputs" SG_MIGRATIONS=db/migration SG_SCHEMA=schema.sql
mistake "neither migrations nor schema" "Give exactly one of the inputs"
mistake "files must be changed or all" "files must be changed or all, not 'some'." SG_MIGRATIONS=db/migration SG_FILES=some
mistake "comment must be true or false" "comment must be true or false, not 'yes'." SG_MIGRATIONS=db/migration SG_COMMENT=yes
mistake "a key needs a project" "Saving needs both api-key and project." SG_SCHEMA=schema.sql SG_API_KEY=sg_x
mistake "saving needs a schema" "Saving needs the schema input" SG_MIGRATIONS=db/migration SG_API_KEY=sg_x SG_PROJECT=p
mistake "a missing folder" "migrations: no such folder: nowhere" SG_MIGRATIONS=nowhere
mistake "a missing schema" "schema: no such file: none.sql" SG_SCHEMA=none.sql
mistake "a missing jar" "cli-jar: no such file: /no/such.jar" SG_MIGRATIONS=db/migration SG_CLI_JAR=/no/such.jar

echo
echo "$tests tests, $failures failed"
[[ $failures -eq 0 ]]
