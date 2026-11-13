#!/usr/bin/env bash
# Rebuilds the PostgreSQL output in engine/src/test/resources/golden/<fixture>/ by running each
# fixture's scripts in a throwaway database on a running PostgreSQL 16 and dumping the result.
#
#   <fixture>/source.sql                    -> <fixture>/pg_dump_16.sql
#   <fixture>/before.sql                    -> <fixture>/before_pg_dump_16.sql
#   <fixture>/before.sql + migration.sql    -> <fixture>/after_pg_dump_16.sql
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

# dump <output> <script> [script ...]: runs the scripts in a fresh database and dumps its schema.
dump() {
    local output="$1"; shift
    local db="sg_golden_$$"
    "${bin}psql" -X -q -d postgres -c "DROP DATABASE IF EXISTS ${db}" -c "CREATE DATABASE ${db}"
    for script in "$@"; do
        "${bin}psql" -X -q -d "$db" -v ON_ERROR_STOP=1 -f "$script"
    done
    "${bin}pg_dump" -d "$db" --schema-only > "$output"
    "${bin}psql" -X -q -d postgres -c "DROP DATABASE ${db}"
}

for fixture in "${fixtures[@]}"; do
    dir="$golden/$fixture"
    if [ -f "$dir/source.sql" ]; then
        dump "$dir/pg_dump_16.sql" "$dir/source.sql"
    fi
    if [ -f "$dir/before.sql" ]; then
        dump "$dir/before_pg_dump_16.sql" "$dir/before.sql"
        dump "$dir/after_pg_dump_16.sql" "$dir/before.sql" "$dir/migration.sql"
    fi
    echo "regenerated $fixture"
done
