# Golden fixtures

Real PostgreSQL output, used to check the parser against what the server actually writes.

Each folder holds `source.sql`, a hand-written script, and `pg_dump_16.sql`, the schema a
PostgreSQL 16.15 server built from that script, written back by `pg_dump --schema-only`.

| Folder | What `source.sql` exercises |
| --- | --- |
| `shop/` | A realistic schema: enum, identity and serial columns, generated column, composite and deferrable keys, partitioned table, six kinds of index, sequences, a view, a function and a trigger |
| `names/` | Everything PostgreSQL has to name itself: unnamed constraints and indexes, serial sequences, repeated names, names long enough to be shortened, duplicate keys that are merged |
| `migrations/` | A schema changed by a run of migrations: every `ALTER TABLE` action the catalog models, renames, a move to another schema, and drops with CASCADE |

`CatalogOracleTest` requires the catalog built from `source.sql` to equal the catalog built
from `pg_dump_16.sql`. `RealDumpTest` checks the parser against the `shop` dump.

`pg_dump_16.sql` is generated. Do not edit it by hand.

## Regenerating

Needs a running PostgreSQL 16 server. From the repository root:

```bash
scripts/regenerate-golden.sh            # every fixture
scripts/regenerate-golden.sh names      # one fixture
```

The script reads the usual `PGHOST`, `PGPORT` and `PGUSER` variables, and `PGBIN` if `psql` and
`pg_dump` are not on the `PATH`. To start a throwaway server on Linux, as a user allowed to
run one (`WORK` is any empty directory that user owns):

```bash
PGBIN=/usr/lib/postgresql/16/bin
WORK=/var/lib/postgresql/sg-golden
$PGBIN/initdb -D $WORK/data -A trust -E UTF8 --locale=C
$PGBIN/pg_ctl -D $WORK/data -o "-p 54329 -c listen_addresses='' -k $WORK" -l $WORK/log -w start
PGHOST=$WORK PGPORT=54329 PGBIN=$PGBIN scripts/regenerate-golden.sh
$PGBIN/pg_ctl -D $WORK/data -m fast -w stop
```

The `\restrict` token in a dump is random and changes on every run; the tests do not depend on it.
