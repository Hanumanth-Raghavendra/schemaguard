#!/usr/bin/env bash
# Rebuilds engine/src/test/resources/golden/<fixture>/pg_dump_16.sql from source.sql by
# loading each source into a throwaway database on a running PostgreSQL 16 and dumping it.
#
# Usage, from the repository root:
#   scripts/regenerate-golden.sh [fixture ...]        (no arguments = every fixture)
#
# Connection settings come from the usual PGHOST, PGPORT and PGUSER variables. PGBIN may point
# at the directory holding psql and pg_dump if they are not on the PATH.
set -euo pipefail

golden="engine/src/test/resources/golden"
bin="${PGBIN:+$PGBIN/}"
fixtures=("$@")
if [ ${#fixtures[@]} -eq 0 ]; then
    for dir in "$golden"/*/; do fixtures+=("$(basename "$dir")"); done
fi

for fixture in "${fixtures[@]}"; do
    db="sg_golden_${fixture}"
    "${bin}psql" -X -q -d postgres -c "DROP DATABASE IF EXISTS ${db}" -c "CREATE DATABASE ${db}"
    "${bin}psql" -X -q -d "$db" -v ON_ERROR_STOP=1 -f "$golden/$fixture/source.sql"
    "${bin}pg_dump" -d "$db" --schema-only > "$golden/$fixture/pg_dump_16.sql"
    "${bin}psql" -X -q -d postgres -c "DROP DATABASE ${db}"
    echo "regenerated $fixture"
done
