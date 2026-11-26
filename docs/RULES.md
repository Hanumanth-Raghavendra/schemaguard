# SchemaGuard rules

<!-- Generated from the rule classes. Do not edit; run the build with -Dgolden.update=true. -->

40 rules. Each one looks at the changes between two schemas and
reports what the direct way of applying a change would do to a running database.
Rules marked *script* need the statements of a migration script to judge, and are only
reported when a script is checked.

| Id | Severity | Category | Rule |
|---|---|---|---|
| [SG-R101](#sg-r101-index-added-without-concurrently) | HIGH | locking | Index is built without CONCURRENTLY |
| [SG-R102](#sg-r102-index-dropped-without-concurrently) | MEDIUM | locking | Index is dropped without CONCURRENTLY |
| [SG-R103](#sg-r103-index-replaced) | HIGH | locking | Index definition changes, so the index is rebuilt |
| [SG-R104](#sg-r104-foreign-key-added-validated) | HIGH | locking | Foreign key is added without NOT VALID |
| [SG-R105](#sg-r105-check-added-validated) | HIGH | locking | CHECK constraint is added without NOT VALID |
| [SG-R106](#sg-r106-not-null-set-with-scan) | HIGH | locking | SET NOT NULL scans the table under a full lock |
| [SG-R107](#sg-r107-primary-key-added) | HIGH | locking | Primary key is added by building its index under a full lock |
| [SG-R108](#sg-r108-unique-added) | HIGH | locking | UNIQUE constraint is added by building its index under a full lock |
| [SG-R109](#sg-r109-constraint-replaced) | HIGH | locking | Constraint definition changes, so the constraint is dropped and added again |
| [SG-R110](#sg-r110-collation-changed-on-indexed-column) | HIGH | locking | Collation change rebuilds the indexes on the column |
| [SG-R111](#sg-r111-lock-timeout) | LOW | locking | Statements that need a full lock should run with a lock timeout |
| [SG-R201](#sg-r201-column-type-rewrite) | HIGH | table rewrite | Column type change rewrites the table |
| [SG-R202](#sg-r202-column-added-with-volatile-default) | HIGH | table rewrite | New column has a volatile default, which rewrites the table |
| [SG-R203](#sg-r203-column-added-with-unknown-default) | MEDIUM | table rewrite | New column has a default that may rewrite the table |
| [SG-R204](#sg-r204-identity-column-added) | HIGH | table rewrite | New identity column rewrites the table |
| [SG-R205](#sg-r205-generated-column-added) | HIGH | table rewrite | New stored generated column rewrites the table |
| [SG-R206](#sg-r206-generated-expression-changed) | HIGH | table rewrite | Generated column changes its expression, so the column is rebuilt |
| [SG-R207](#sg-r207-persistence-changed) | HIGH | table rewrite | Switching between LOGGED and UNLOGGED rewrites the table |
| [SG-R208](#sg-r208-partitioning-changed) | HIGH | table rewrite | Partitioning cannot be changed in place |
| [SG-R209](#sg-r209-enum-values-reordered) | HIGH | table rewrite | Enum values cannot be reordered in place |
| [SG-R210](#sg-r210-column-order-changed) | LOW | table rewrite | Column order cannot be changed in place |
| [SG-R301](#sg-r301-table-dropped) | CRITICAL | data loss | Table is dropped with all its rows |
| [SG-R302](#sg-r302-column-dropped) | CRITICAL | data loss | Column is dropped with all its values |
| [SG-R303](#sg-r303-enum-value-removed) | HIGH | data loss | Enum value is removed |
| [SG-R304](#sg-r304-column-type-narrowed) | HIGH | data loss | New column type holds less than the old one |
| [SG-R305](#sg-r305-possible-rename) | HIGH | data loss | Possible rename would be applied as drop and add |
| [SG-R306](#sg-r306-possible-enum-value-rename) | MEDIUM | data loss | Possible enum value rename would be applied as remove and add |
| [SG-R401](#sg-r401-not-null-column-without-default) | HIGH | can fail | New NOT NULL column has no default |
| [SG-R402](#sg-r402-column-type-conversion) | MEDIUM | can fail | Column changes to a different kind of type |
| [SG-R403](#sg-r403-concurrently-in-transaction) | HIGH | can fail | CONCURRENTLY is used inside a transaction block *(script)* |
| [SG-R501](#sg-r501-default-dropped-on-required-column) | HIGH | compatibility | Default is removed from a NOT NULL column |
| [SG-R502](#sg-r502-not-null-dropped) | LOW | compatibility | Column starts to accept NULL |
| [SG-R503](#sg-r503-constraint-dropped) | MEDIUM | compatibility | Constraint is dropped |
| [SG-R504](#sg-r504-sequence-dropped) | MEDIUM | compatibility | Sequence is dropped |
| [SG-R505](#sg-r505-identity-added-to-existing-column) | HIGH | compatibility | Identity added to a column that already has values starts at 1 |
| [SG-R506](#sg-r506-identity-removed) | MEDIUM | compatibility | Identity is removed and nothing generates the value any more |
| [SG-R507](#sg-r507-identity-made-always) | LOW | compatibility | Identity changes to GENERATED ALWAYS |
| [SG-R508](#sg-r508-enum-value-added) | LOW | compatibility | New enum value needs care in two places |
| [SG-R509](#sg-r509-inheritance-changed) | MEDIUM | compatibility | Table changes its parent tables |
| [SG-R510](#sg-r510-renamed-in-place) | HIGH | compatibility | Table, column or enum value is renamed in one step *(script)* |

## Locking

The change holds a lock that makes the application's queries wait.

### SG-R101 index-added-without-concurrently

**Index is built without CONCURRENTLY** (HIGH)

Why: CREATE INDEX takes a SHARE lock on the table for the whole build. Reads continue, but every INSERT, UPDATE and DELETE waits, and on a large table the build takes minutes or hours.

Safer: Use CREATE INDEX CONCURRENTLY. It builds the index without blocking writes. It cannot run inside a transaction, and if it fails it leaves an invalid index that has to be dropped before trying again. With Flyway, set postgresql.transactional.lock=false and connect directly, not through a transaction pooler: under Flyway's default lock the build waits forever for Flyway's own open transaction.

### SG-R102 index-dropped-without-concurrently

**Index is dropped without CONCURRENTLY** (MEDIUM)

Why: DROP INDEX takes an ACCESS EXCLUSIVE lock on the table. The drop itself is quick, but it has to wait for every running query on the table, and while it waits all new queries queue up behind it. Queries that used the index also become slower from that moment.

Safer: Use DROP INDEX CONCURRENTLY, which waits without blocking anyone. First check that the index is no longer used: pg_stat_user_indexes shows how often it is scanned.

### SG-R103 index-replaced

**Index definition changes, so the index is rebuilt** (HIGH)

Why: PostgreSQL cannot change the columns, the method or the WHERE clause of an existing index. The direct route drops the index and builds it again: writes are blocked during the build, and queries have no index in between.

Safer: Build the new definition under a temporary name with CREATE INDEX CONCURRENTLY, drop the old index with DROP INDEX CONCURRENTLY, then rename the new one with ALTER INDEX ... RENAME.

### SG-R104 foreign-key-added-validated

**Foreign key is added without NOT VALID** (HIGH)

Why: Adding a foreign key checks every existing row against the referenced table. For the whole check it holds a SHARE ROW EXCLUSIVE lock on both tables, which blocks all writes to them. It also fails if one row points to a row that does not exist.

Safer: Add the constraint with NOT VALID: it then applies to new rows only and is quick. Run ALTER TABLE ... VALIDATE CONSTRAINT as a second statement; it checks the existing rows without blocking reads or writes.

### SG-R105 check-added-validated

**CHECK constraint is added without NOT VALID** (HIGH)

Why: Adding a CHECK constraint reads every existing row to test the condition, and holds an ACCESS EXCLUSIVE lock while it does: neither reads nor writes get through. It also fails if one row breaks the condition.

Safer: Add the constraint with NOT VALID, which is quick, then run ALTER TABLE ... VALIDATE CONSTRAINT as a second statement. Validation reads the rows under a lock that lets reads and writes continue.

### SG-R106 not-null-set-with-scan

**SET NOT NULL scans the table under a full lock** (HIGH)

Why: To accept NOT NULL, PostgreSQL has to be sure no row holds a NULL. It reads the whole table to find out, holding an ACCESS EXCLUSIVE lock that blocks reads and writes. If one NULL is found, the statement fails.

Safer: Add CHECK (column IS NOT NULL) NOT VALID, run VALIDATE CONSTRAINT, then SET NOT NULL: PostgreSQL 12 and later take the valid constraint as proof and skip the scan. Drop the constraint afterwards. Fill any NULLs before validating.

### SG-R107 primary-key-added

**Primary key is added by building its index under a full lock** (HIGH)

Why: ADD PRIMARY KEY builds a unique index and, if needed, checks the columns for NULLs. All of that happens under an ACCESS EXCLUSIVE lock, so reads and writes wait for the whole build. It fails if rows hold duplicates or NULLs.

Safer: Build the index first with CREATE UNIQUE INDEX CONCURRENTLY and make the columns NOT NULL the safe way. Then attach the index with ALTER TABLE ... ADD CONSTRAINT ... PRIMARY KEY USING INDEX, which only needs a brief lock.

### SG-R108 unique-added

**UNIQUE constraint is added by building its index under a full lock** (HIGH)

Why: ADD CONSTRAINT ... UNIQUE builds a unique index under an ACCESS EXCLUSIVE lock, so reads and writes wait for the whole build. It fails if existing rows hold duplicate values.

Safer: Build the index first with CREATE UNIQUE INDEX CONCURRENTLY, then attach it with ALTER TABLE ... ADD CONSTRAINT ... UNIQUE USING INDEX, which only needs a brief lock. Look for duplicates before starting.

### SG-R109 constraint-replaced

**Constraint definition changes, so the constraint is dropped and added again** (HIGH)

Why: PostgreSQL cannot alter what a constraint checks. The direct route drops it and adds the new definition, which checks every row again under a lock. If the two statements are not in one transaction, invalid rows can slip in between them.

Safer: Add the new definition under a new name with NOT VALID, run VALIDATE CONSTRAINT, and only then drop the old constraint. The table is never without the rule and never locked for long. For a key, build the new unique index concurrently first.

### SG-R110 collation-changed-on-indexed-column

**Collation change rebuilds the indexes on the column** (HIGH)

Why: A collation decides how text sorts, so an index sorted by the old collation is useless for the new one. PostgreSQL rebuilds every index on the column, reading the whole table under an ACCESS EXCLUSIVE lock that blocks reads and writes.

Safer: If only some queries need the other collation, leave the column alone and write COLLATE in those queries, or add a second index with the new collation using CREATE INDEX CONCURRENTLY.

### SG-R111 lock-timeout

**Statements that need a full lock should run with a lock timeout** (LOW)

Why: An ACCESS EXCLUSIVE lock has to wait for every query already running on the table, even a slow report. While it waits, every new query on that table waits behind it. A statement that takes a millisecond can so stop an application for minutes.

Safer: Run SET lock_timeout = '5s' before the migration and try again if it times out. A failed attempt costs nothing; a waiting one blocks everybody.

## Table rewrite

The change writes the whole table again while everything else is locked out.

### SG-R201 column-type-rewrite

**Column type change rewrites the table** (HIGH)

Why: The stored bytes of the old type are not valid for the new one. PostgreSQL writes every row into a new copy of the table and rebuilds every index, all under an ACCESS EXCLUSIVE lock: no reads, no writes, for as long as it takes. It also needs free disk space for the second copy.

Safer: Add a new column of the new type, fill it in batches, keep it in step with a trigger, then switch the application over and drop the old column. Some changes need no rewrite at all: a longer varchar, varchar to text, or a numeric with more digits and the same scale.

### SG-R202 column-added-with-volatile-default

**New column has a volatile default, which rewrites the table** (HIGH)

Why: A constant default is stored once in the catalog. A volatile default, such as random(), gen_random_uuid() or the nextval() behind serial, gives a different value for each row, so each existing row has to be written. PostgreSQL writes every row into a new copy of the table and rebuilds every index, all under an ACCESS EXCLUSIVE lock: no reads, no writes, for as long as it takes. It also needs free disk space for the second copy.

Safer: Add the column without a default, then set the default with ALTER COLUMN ... SET DEFAULT, which only affects new rows. Fill the existing rows in batches.

### SG-R203 column-added-with-unknown-default

**New column has a default that may rewrite the table** (MEDIUM)

Why: The default calls a function that is not built into PostgreSQL, so SchemaGuard cannot tell whether it is volatile. If it is, every existing row has to be written again under an ACCESS EXCLUSIVE lock.

Safer: Check the function with SELECT provolatile FROM pg_proc WHERE proname = '...'. If the answer is v, add the column without a default, set the default afterwards and fill the rows in batches.

### SG-R204 identity-column-added

**New identity column rewrites the table** (HIGH)

Why: An identity column needs a number in every row, so PostgreSQL draws one from the sequence for each existing row. PostgreSQL writes every row into a new copy of the table and rebuilds every index, all under an ACCESS EXCLUSIVE lock: no reads, no writes, for as long as it takes. It also needs free disk space for the second copy.

Safer: Add a plain nullable column, fill it in batches from a sequence, make it NOT NULL the safe way, then attach the identity with ALTER COLUMN ... ADD GENERATED ... AS IDENTITY, which does not rewrite. Start the identity above the highest number used.

### SG-R205 generated-column-added

**New stored generated column rewrites the table** (HIGH)

Why: A stored generated column keeps its computed value in each row, so PostgreSQL computes it for every existing row. PostgreSQL writes every row into a new copy of the table and rebuilds every index, all under an ACCESS EXCLUSIVE lock: no reads, no writes, for as long as it takes. It also needs free disk space for the second copy.

Safer: Add a plain column, fill it in batches and keep it current with a trigger. If the value is only read now and then, compute it in the query or in a view instead.

### SG-R206 generated-expression-changed

**Generated column changes its expression, so the column is rebuilt** (HIGH)

Why: PostgreSQL 16 cannot change the expression of a generated column, nor turn a plain column into a generated one. The column has to be dropped and added again, and adding it computes the value for every row: a full table rewrite under an ACCESS EXCLUSIVE lock. A plain column that becomes generated loses its stored values.

Safer: Add a second column with the new expression the safe way (plain column, filled in batches, kept current by a trigger), move the readers over, then drop the old column.

### SG-R207 persistence-changed

**Switching between LOGGED and UNLOGGED rewrites the table** (HIGH)

Why: A logged table is written to the write-ahead log and an unlogged one is not, so changing between them means writing all rows again. PostgreSQL writes every row into a new copy of the table and rebuilds every index, all under an ACCESS EXCLUSIVE lock: no reads, no writes, for as long as it takes. It also needs free disk space for the second copy. An unlogged table is also emptied after a crash and is not copied to replicas.

Safer: Create a new table with the wanted persistence, copy the rows in batches while a trigger keeps both in step, then swap the names in one short transaction.

### SG-R208 partitioning-changed

**Partitioning cannot be changed in place** (HIGH)

Why: PostgreSQL decides how a table is partitioned when the table is created and offers no statement to change it later. Reaching the new schema means creating a new table and copying every row into it.

Safer: Create the partitioned table under a new name, copy the rows in batches while a trigger keeps both in step, then swap the names in one short transaction. A plain table can also be attached as the first partition of a new partitioned table without copying.

### SG-R209 enum-values-reordered

**Enum values cannot be reordered in place** (HIGH)

Why: The order of enum values is fixed when each value is created. To change it, the type has to be created again and every column that uses it converted, which rewrites each of those tables under an ACCESS EXCLUSIVE lock. The new order also changes how the values sort and compare.

Safer: Leave the order alone unless sorting depends on it; ORDER BY with a CASE expression or a lookup table gives any order without touching the type. New values can be placed with ADD VALUE ... BEFORE or AFTER.

### SG-R210 column-order-changed

**Column order cannot be changed in place** (LOW)

Why: PostgreSQL always adds a new column at the end and has no statement to move one. Reaching exactly the column order of the new schema means creating the table again and copying every row, which is rarely worth it.

Safer: Accept the order PostgreSQL gives. Column order has no effect on queries that name their columns. To keep the schema file and the database the same, list new columns last in the file.

## Data loss

The change destroys data, or would if applied the direct way.

### SG-R301 table-dropped

**Table is dropped with all its rows** (CRITICAL)

Why: DROP TABLE removes every row at once and cannot be undone after the transaction commits. Only a backup brings the data back. Application code that still uses the table fails from that moment.

Safer: First release application code that no longer uses the table. Then rename the table instead of dropping it, wait a release or two to be sure nothing misses it, take a backup, and drop it last.

### SG-R302 column-dropped

**Column is dropped with all its values** (CRITICAL)

Why: DROP COLUMN makes the values of the column unreachable in every row, and they cannot be brought back without a backup. Application code that still selects or inserts the column fails at once, including code running during the deployment.

Safer: First release application code that no longer reads or writes the column, then drop it in a later migration. Keep a backup of the values until the change has proven itself.

### SG-R303 enum-value-removed

**Enum value is removed** (HIGH)

Why: PostgreSQL has no statement to remove a value from an enum. The type has to be created again without the value and every column converted to it, which rewrites those tables. The conversion fails while any row still holds the value, so those rows have to be changed or deleted first.

Safer: Stop writing the value and update the rows that hold it. Then create the new type, convert each column with ALTER COLUMN ... TYPE ... USING, and drop the old type. Often it is simpler to leave the value in the type and stop using it.

### SG-R304 column-type-narrowed

**New column type holds less than the old one** (HIGH)

Why: A value that does not fit the new type makes the migration fail half way: a text longer than the new limit, a number larger than the new range. Some values are changed without any error: numbers are rounded to the new scale and timestamps to the new precision.

Safer: Look at the existing values first, for example max(length(column)) or max(column). To be safe, add a CHECK constraint with the new limit as NOT VALID and validate it: once it holds, the type change cannot fail.

### SG-R305 possible-rename

**Possible rename would be applied as drop and add** (HIGH)

Why: Two schemas alone cannot tell a rename from a drop followed by an add. The direct route does the second: it drops the old one and creates an empty new one, and the data is gone. If the intent was a rename, that is data loss nobody asked for. A real rename keeps the data, but it still breaks every query that uses the old name.

Safer: If it is a rename, write ALTER TABLE ... RENAME (TO or COLUMN) yourself. To rename without breaking the running application, add the new name next to the old one, write to both, move the readers over, and remove the old name last. If it is not a rename, nothing needs to change.

### SG-R306 possible-enum-value-rename

**Possible enum value rename would be applied as remove and add** (MEDIUM)

Why: A removed enum value and an added one may be the same value under a new name. Removing a value means creating the type again and changing every row that holds it; ALTER TYPE ... RENAME VALUE does the same job by changing one catalog row.

Safer: If it is a rename, use ALTER TYPE ... RENAME VALUE. It takes no table lock and keeps every row, but the application must accept the new name at the same moment.

## Can fail

The change is rejected by PostgreSQL when the existing rows do not allow it.

### SG-R401 not-null-column-without-default

**New NOT NULL column has no default** (HIGH)

Why: PostgreSQL has to put something into the new column of every existing row. Without a default that something is NULL, which NOT NULL forbids, so the statement fails as soon as the table has one row. It works on an empty test database and fails in production.

Safer: Give the column a constant default: since PostgreSQL 11 that does not rewrite the table. Or add it nullable, fill the rows in batches, and set NOT NULL the safe way afterwards.

### SG-R402 column-type-conversion

**Column changes to a different kind of type** (MEDIUM)

Why: PostgreSQL has to convert every value, and usually needs a USING expression that says how. One value that cannot be converted, such as a text that is not a number, aborts the whole migration after it may have run for a long time.

Safer: Test the conversion on a copy of the production data. To find the bad values first, run the USING expression in a SELECT. For large tables, add a new column, convert in batches and swap.

### SG-R403 concurrently-in-transaction

**CONCURRENTLY is used inside a transaction block** (HIGH)

Why: CREATE INDEX CONCURRENTLY and DROP INDEX CONCURRENTLY work in several transactions of their own, so PostgreSQL refuses them inside BEGIN ... COMMIT. The script stops at that statement. Migration tools that wrap every file in a transaction cause the same failure.

Safer: Move the statement out of the transaction block, into a migration of its own, and switch off the tool's automatic transaction for that file.

## Compatibility

The change can break an application that still expects the old schema.

### SG-R501 default-dropped-on-required-column

**Default is removed from a NOT NULL column** (HIGH)

Why: Application code that leaves the column out of its INSERT statements has relied on the default. Without it the column gets NULL, NOT NULL rejects that, and those inserts start to fail the moment the migration runs.

Safer: First release application code that always supplies a value for the column. Remove the default in a later migration.

### SG-R502 not-null-dropped

**Column starts to accept NULL** (LOW)

Why: The database change is quick and safe. The risk is in the code that reads the column: application code, reports and exports written when the column could not be NULL may fail or show wrong results on the first NULL.

Safer: Check the readers of the column before anything writes a NULL into it.

### SG-R503 constraint-dropped

**Constraint is dropped** (MEDIUM)

Why: A constraint is a promise the database keeps for the application. Once it is gone, rows that break the rule can be written, and they stay: adding the constraint back later fails because of them. Dropping a primary key or unique constraint also drops its index, so lookups that used it become slow.

Safer: Drop a constraint only when the rule it enforced is really gone. To change a rule, add the new constraint before dropping the old one.

### SG-R504 sequence-dropped

**Sequence is dropped** (MEDIUM)

Why: Everything that calls nextval on the sequence fails once it is gone: application code, functions, and column defaults. The current position of the sequence is lost as well, so a sequence created again under the same name starts from 1 and hands out numbers that were used before.

Safer: Search the application and the database for the sequence name before dropping it. If the numbers must continue, note the last value with SELECT last_value FROM the sequence first.

### SG-R505 identity-added-to-existing-column

**Identity added to a column that already has values starts at 1** (HIGH)

Why: ADD GENERATED ... AS IDENTITY creates a new sequence that starts at 1. The column already holds numbers, so the first inserts get values that are taken and fail with a duplicate key error. This is true even when the column was a serial before: the identity does not take over the old sequence.

Safer: Start the identity above the highest value in use: ADD GENERATED ... AS IDENTITY (START WITH n), or run ALTER COLUMN ... RESTART WITH n in the same transaction.

### SG-R506 identity-removed

**Identity is removed and nothing generates the value any more** (MEDIUM)

Why: Inserts have relied on the database to fill the column. After DROP IDENTITY the column has no source of values: an INSERT that leaves it out gets NULL, and as identity columns are NOT NULL, the insert fails.

Safer: Give the column a default in the same migration, for example nextval of a sequence that continues from the last value, or first release application code that supplies the value itself.

### SG-R507 identity-made-always

**Identity changes to GENERATED ALWAYS** (LOW)

Why: With GENERATED BY DEFAULT an INSERT may supply its own value for the column. With GENERATED ALWAYS that INSERT is rejected. Data imports, copies between environments and tests that set ids often do exactly that.

Safer: Check that nothing sets the column itself. The places that must do so can add OVERRIDING SYSTEM VALUE to their INSERT.

### SG-R508 enum-value-added

**New enum value needs care in two places** (LOW)

Why: ALTER TYPE ... ADD VALUE takes no table lock, but the new value cannot be used in the transaction that adds it: a migration that adds the value and then writes it in the same transaction fails. Application code that reads the column and does not know the value yet may also fail on the first row that holds it.

Safer: Add the value in a migration of its own. Release the code that can read the new value before the code that writes it.

### SG-R509 inheritance-changed

**Table changes its parent tables** (MEDIUM)

Why: A query on a parent table also returns the rows of its children. Attaching or detaching a parent therefore changes the result of every query on that parent, without any change to the query. Attaching also needs an ACCESS EXCLUSIVE lock on the child and requires it to have all the columns and CHECK constraints of the parent already.

Safer: Check every query that reads the parent tables, and use ONLY where the children must not be included.

### SG-R510 renamed-in-place

**Table, column or enum value is renamed in one step** (HIGH)

Why: The rename itself is quick and keeps the data. But the old name is gone the moment it commits, and every query of the running application that still uses it fails. Application and database cannot be deployed at the same instant, so there is always a window where one of them is wrong.

Safer: Add the new name next to the old one: a new column kept in step by a trigger, or a view under the new table name. Move the application over, then remove the old name in a later migration.
