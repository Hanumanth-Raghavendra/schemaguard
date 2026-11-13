# Golden fixtures

Real PostgreSQL output, used to check the parser against what the server actually writes.

| Folder | `source.sql` | `pg_dump_16.sql` |
| --- | --- | --- |
| `shop/` | A hand-written schema: enum, identity and serial columns, generated column, composite and deferrable keys, partitioned table, six kinds of index, sequences, a view, a function and a trigger | The same schema as dumped by `pg_dump --schema-only` from PostgreSQL 16.15 on 2026-11-13 |

`pg_dump_16.sql` is generated. Do not edit it by hand.

## Regenerating

Needs a PostgreSQL 16 server. From the repository root, on Linux as a user allowed to run one
(`WORK` is any empty directory that user owns):

```bash
PGBIN=/usr/lib/postgresql/16/bin
WORK=/var/lib/postgresql/sg-golden
$PGBIN/initdb -D $WORK/data -A trust -E UTF8 --locale=C
$PGBIN/pg_ctl -D $WORK/data -o "-p 54329 -c listen_addresses='' -k $WORK" -l $WORK/log -w start
$PGBIN/createdb -h $WORK -p 54329 sg
$PGBIN/psql -h $WORK -p 54329 -d sg -v ON_ERROR_STOP=1 -f engine/src/test/resources/golden/shop/source.sql
$PGBIN/pg_dump -h $WORK -p 54329 -d sg --schema-only > engine/src/test/resources/golden/shop/pg_dump_16.sql
$PGBIN/pg_ctl -D $WORK/data -m fast -w stop
```

The `\restrict` token in the dump is random and changes on every run; the tests do not depend on it.
