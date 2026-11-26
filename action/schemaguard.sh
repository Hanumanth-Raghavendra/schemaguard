#!/usr/bin/env bash
# The SchemaGuard GitHub Action (see action.yml for its inputs, which arrive as SG_* variables).
#
# 1. Find Java 21 and the SchemaGuard CLI jar (given, or downloaded from the release and checked).
# 2. Decide what to check: on a pull request, the migrations it changes (or its version of the schema
#    against the base branch's); otherwise every migration.
# 3. Run the CLI, turn its diagnostics into annotations, write the report to the job summary and,
#    on a pull request, to one comment that later pushes update.
# 4. Exit with the CLI's code: 0 within the limit, 1 above it, 2 to 4 for problems.
set -euo pipefail

readonly REPOSITORY_URL="https://github.com/Hanumanth-Raghavendra/schemaguard"

error() {
  echo "::error title=SchemaGuard::$*"
  exit 2
}

work=$(mktemp -d "${RUNNER_TEMP:-/tmp}/schemaguard.XXXXXX")

# ------------------------------------------------------------------ inputs

migrations=${SG_MIGRATIONS:-}
schema=${SG_SCHEMA:-}
files=${SG_FILES:-changed}
fail_above=${SG_FAIL_ABOVE:-high}
comment=${SG_COMMENT:-true}
event=${SG_EVENT:-}

if [[ -n $migrations && -n $schema ]] || [[ -z $migrations && -z $schema ]]; then
  error "Give exactly one of the inputs migrations (a folder) and schema (a file)."
fi
case $files in changed | all) ;; *) error "files must be changed or all, not '$files'." ;; esac
case $comment in true | false) ;; *) error "comment must be true or false, not '$comment'." ;; esac
if [[ -n ${SG_API_KEY:-} || -n ${SG_PROJECT:-} ]]; then
  [[ -n ${SG_API_KEY:-} && -n ${SG_PROJECT:-} ]] || error "Saving needs both api-key and project."
  [[ -n $schema ]] || error "Saving needs the schema input: a folder of migrations is not a whole schema."
  echo "::add-mask::$SG_API_KEY"
fi

# Paths are relative to the repository root, which is where the job's steps run.
migrations=${migrations%/}
migrations=${migrations#./}
schema=${schema#./}

pull_request=false
[[ $event == pull_request || $event == pull_request_target ]] && pull_request=true

# ------------------------------------------------------------------ Java 21 and the CLI

java_major() {
  "$1" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ { split($2, v, "."); print v[1] }'
}

# Sets java_bin. (The finders set variables rather than print, so error() is never inside $(...).)
find_java() {
  local candidate
  if candidate=$(command -v java) && [[ $(java_major "$candidate") -ge 21 ]]; then
    java_bin=$candidate
    return
  fi
  # GitHub's hosted runners keep a Java 21 here; using it leaves the job's own JAVA_HOME alone.
  for candidate in "${JAVA_HOME_21_X64:-}" "${JAVA_HOME_21_ARM64:-}" "${JAVA_HOME_21_arm64:-}"; do
    if [[ -n $candidate && -x $candidate/bin/java ]]; then
      java_bin=$candidate/bin/java
      return
    fi
  done
  error "SchemaGuard needs Java 21 or newer: add actions/setup-java with java-version 21 before this step."
}

sha256_check() {
  if command -v sha256sum > /dev/null; then sha256sum --check --status "$1"; else shasum -a 256 --check --status "$1"; fi
}

# Sets jar.
find_jar() {
  if [[ -n ${SG_CLI_JAR:-} ]]; then
    [[ -f $SG_CLI_JAR ]] || error "cli-jar: no such file: $SG_CLI_JAR"
    jar=$SG_CLI_JAR
    return
  fi
  local version=${SG_CLI_VERSION:-}
  if [[ -z $version && ${SG_ACTION_REF:-} =~ ^v([0-9].*)$ ]]; then
    version=${BASH_REMATCH[1]}
  fi
  if [[ -z $version ]]; then
    error "Use the action at a release tag (Hanumanth-Raghavendra/schemaguard/action@v0.6.0), or set cli-version or cli-jar."
  fi
  [[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.]+)?$ ]] || error "cli-version is not a version: $version"
  local name="schemaguard-cli-$version.jar"
  local url="$REPOSITORY_URL/releases/download/v$version/$name"
  mkdir -p "$work/cli"
  if ! curl -fsSL --retry 3 -o "$work/cli/$name" "$url" || ! curl -fsSL --retry 3 -o "$work/cli/$name.sha256" "$url.sha256"; then
    error "Cannot download $url. Is v$version a SchemaGuard release with the CLI attached?"
  fi
  (cd "$work/cli" && sha256_check "$name.sha256") || error "$name does not match its published checksum."
  jar=$work/cli/$name
}

java_bin=""
jar=""
find_java
find_jar
cli() { "$java_bin" -jar "$jar" "$@"; }
version=$(cli --version | head -n 1)

# ------------------------------------------------------------------ what to check

# Makes a commit of the base branch available in a shallow checkout.
have_commit() {
  git cat-file -e "$1^{commit}" 2> /dev/null || git fetch --quiet --no-tags --depth=1 origin "$1"
}

# The pull request's base, or for a push the commit before it; empty when there is none.
base=""
if [[ $pull_request == true ]]; then
  base=${SG_BASE_SHA:-}
elif [[ -n ${SG_PUSH_BEFORE:-} && ! ${SG_PUSH_BEFORE} =~ ^0+$ ]]; then
  base=$SG_PUSH_BEFORE
fi
[[ -z $base ]] || have_commit "$base" || error "Cannot fetch the base commit $base."

args=()
nothing=""
if [[ -n $migrations ]]; then
  [[ -d $migrations ]] || error "migrations: no such folder: $migrations"
  target=$migrations
  if [[ $files == changed && $pull_request == true ]]; then
    changed=()
    while IFS= read -r path; do
      name=${path##*/}
      # Files directly in the folder; Flyway's repeatable and undo files are not checked.
      if [[ ${path%/*} == "$migrations" && $name == *.sql && ! $name =~ ^(R|U[0-9][0-9._]*)__ ]]; then
        changed+=("$path")
      fi
    done < <(git diff --name-only --diff-filter=d "$base" HEAD -- "$migrations")
    if [[ ${#changed[@]} -eq 0 ]]; then
      nothing="No migrations in \`$migrations\` changed in this pull request."
    else
      args=(lint --migrations "$migrations" "${changed[@]}")
    fi
  else
    args=(lint --migrations "$migrations")
  fi
else
  [[ -f $schema ]] || error "schema: no such file: $schema"
  target=$schema
  if [[ -z $base ]]; then
    nothing="There is no earlier version of \`$schema\` to compare with."
  elif git diff --quiet "$base" HEAD -- "$schema"; then
    nothing="\`$schema\` did not change."
  else
    # A schema file new in this change is compared with an empty schema.
    git show "$base:$schema" > "$work/before.sql" 2> /dev/null || : > "$work/before.sql"
    args=(report "$work/before.sql" "$schema")
  fi
fi

# ------------------------------------------------------------------ run the CLI

report="$work/report.md"
code=0
score=""
level=""
if [[ -n $nothing ]]; then
  echo "$nothing"
  printf '%s\n' "$nothing" > "$report"
else
  cli "${args[@]}" --fail-above "$fail_above" --format markdown > "$report" 2> "$work/stderr.txt" || code=$?
  cat "$work/stderr.txt" >&2
  if [[ $code -ge 2 ]]; then
    echo "::error title=SchemaGuard::The check could not run (exit code $code); see the log above."
    exit "$code"
  fi
  cli "${args[@]}" --format json 2> /dev/null > "$work/report.json"
  score=$(jq -r '.score.value' "$work/report.json")
  level=$(jq -r '.score.level' "$work/report.json")

  # Diagnostics about files in the repository become annotations on those lines.
  diagnostic='^(.+):([0-9]+):([0-9]+): (error|warning): (.*)$'
  while IFS= read -r line; do
    if [[ $line =~ $diagnostic && -f ${BASH_REMATCH[1]} && ${BASH_REMATCH[1]} != "$work"/* ]]; then
      message=${BASH_REMATCH[5]//%/%25}
      echo "::${BASH_REMATCH[4]} file=${BASH_REMATCH[1]},line=${BASH_REMATCH[2]},col=${BASH_REMATCH[3]}::$message"
    fi
  done < "$work/stderr.txt"
fi

# ------------------------------------------------------------------ the report

marker="<!-- schemaguard: $target -->"
body="$work/comment.md"
{
  echo "$marker"
  if [[ -z $nothing ]]; then
    if [[ $code -eq 0 ]]; then
      echo "**SchemaGuard passed:** the risk, $score/100, is within \`fail-above: $fail_above\`."
    else
      verdict=$(grep '^schemaguard: ' "$work/stderr.txt" | tail -n 1 || true)
      echo "**SchemaGuard failed:** ${verdict#schemaguard: }"
    fi
    echo
  fi
  cat "$report"
  echo
  echo "<sub>$version, commit $(git rev-parse --short HEAD).</sub>"
} > "$body"

# A comment holds 65,536 characters; the job summary always has the whole report.
if [[ $(wc -c < "$body") -gt 60000 ]]; then
  head -c 60000 "$body" > "$work/short.md"
  printf '\n\n_The report is too long for a comment: the whole of it is in the job summary._\n' >> "$work/short.md"
  comment_file="$work/short.md"
else
  comment_file="$body"
fi

if [[ -n ${GITHUB_STEP_SUMMARY:-} ]]; then
  cat "$body" >> "$GITHUB_STEP_SUMMARY"
fi

if [[ $comment == true && $pull_request == true && -n ${SG_PR:-} ]]; then
  existing=""
  if pages=$(gh api --paginate "repos/$SG_REPO/issues/$SG_PR/comments?per_page=100" 2> "$work/gh.txt"); then
    existing=$(jq -rs --arg marker "$marker" '[.[][] | select(.body | startswith($marker)) | .id][0] // empty' <<< "$pages")
  fi
  posted=true
  if [[ -n $existing ]]; then
    gh api --silent -X PATCH "repos/$SG_REPO/issues/comments/$existing" -F "body=@$comment_file" 2>> "$work/gh.txt" || posted=false
  elif [[ -z $nothing ]]; then
    # Nothing to report and no earlier comment to correct: stay quiet.
    gh api --silent -X POST "repos/$SG_REPO/issues/$SG_PR/comments" -F "body=@$comment_file" 2>> "$work/gh.txt" || posted=false
  fi
  if [[ $posted == false ]]; then
    echo "::warning title=SchemaGuard::Could not comment on the pull request (a pull request from a fork gets a read-only token, and the workflow needs pull-requests: write). The report is in the job summary."
  fi
fi

# ------------------------------------------------------------------ outputs and saving

if [[ -n ${GITHUB_OUTPUT:-} ]]; then
  {
    echo "score=$score"
    echo "level=$level"
    echo "exit-code=$code"
    echo "report=$body"
  } >> "$GITHUB_OUTPUT"
fi

if [[ -n ${SG_API_KEY:-} && $event == push && ${SG_REF:-} == "refs/heads/${SG_DEFAULT_BRANCH:-}" ]]; then
  SCHEMAGUARD_KEY=$SG_API_KEY cli save --project "$SG_PROJECT" --label "$(git rev-parse --short HEAD)" "$schema" \
    || error "Saving the schema to the SchemaGuard project failed; see the log above."
fi

exit "$code"
