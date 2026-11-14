package io.github.hanumanthraghavendra.schemaguard.engine.rules;

import java.util.Optional;

/**
 * What PostgreSQL does to a table for each kind of schema change: the lock it takes and the
 * work it does while holding it.
 *
 * <p>This is the knowledge the risk rules reason with. None of it is taken on trust: every
 * line was measured on a running server by {@code scripts/measure-locks.sh}, the measurements
 * are stored in {@code src/test/resources/locks}, and a test fails if this table and the
 * measurements ever disagree. Supporting a new PostgreSQL version starts with running the
 * script against it.
 */
public enum Operation {
    ADD_COLUMN("ADD COLUMN", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    ADD_COLUMN_CONSTANT_DEFAULT("ADD COLUMN with a constant default", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    ADD_COLUMN_NOT_NULL_DEFAULT("ADD COLUMN ... NOT NULL with a constant default", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    ADD_COLUMN_STABLE_DEFAULT("ADD COLUMN with a stable default such as now()", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    ADD_COLUMN_VOLATILE_DEFAULT("ADD COLUMN with a volatile default", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.REWRITE),
    ADD_COLUMN_SERIAL("ADD COLUMN ... serial", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.REWRITE),
    ADD_COLUMN_IDENTITY("ADD COLUMN ... GENERATED AS IDENTITY", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.REWRITE),
    ADD_COLUMN_GENERATED("ADD COLUMN ... GENERATED ALWAYS AS (...) STORED", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.REWRITE),
    ADD_COLUMN_WITH_FOREIGN_KEY("ADD COLUMN ... REFERENCES", LockLevel.ACCESS_EXCLUSIVE, LockLevel.SHARE_ROW_EXCLUSIVE, TableWork.NONE),
    DROP_COLUMN("DROP COLUMN", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    ALTER_TYPE_REWRITE("ALTER COLUMN ... TYPE", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.REWRITE),
    ALTER_TYPE_NO_REWRITE("ALTER COLUMN ... TYPE, binary compatible", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    ALTER_COLLATION("ALTER COLUMN ... TYPE ... COLLATE", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    ALTER_COLLATION_INDEXED("ALTER COLUMN ... TYPE ... COLLATE on an indexed column", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.SCAN),
    SET_NOT_NULL("ALTER COLUMN ... SET NOT NULL", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.SCAN),
    SET_NOT_NULL_WITH_VALID_CHECK("ALTER COLUMN ... SET NOT NULL, proven by a valid CHECK", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    DROP_NOT_NULL("ALTER COLUMN ... DROP NOT NULL", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    SET_DEFAULT("ALTER COLUMN ... SET DEFAULT", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    DROP_DEFAULT("ALTER COLUMN ... DROP DEFAULT", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    ADD_IDENTITY("ALTER COLUMN ... ADD GENERATED AS IDENTITY", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    SET_IDENTITY("ALTER COLUMN ... SET GENERATED", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    DROP_IDENTITY("ALTER COLUMN ... DROP IDENTITY", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    DROP_EXPRESSION("ALTER COLUMN ... DROP EXPRESSION", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    ADD_CHECK("ADD CONSTRAINT ... CHECK", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.SCAN),
    ADD_CHECK_NOT_VALID("ADD CONSTRAINT ... CHECK ... NOT VALID", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    VALIDATE_CHECK("VALIDATE CONSTRAINT on a CHECK", LockLevel.SHARE_UPDATE_EXCLUSIVE, null, TableWork.SCAN),
    ADD_FOREIGN_KEY("ADD CONSTRAINT ... FOREIGN KEY", LockLevel.SHARE_ROW_EXCLUSIVE, LockLevel.SHARE_ROW_EXCLUSIVE, TableWork.SCAN),
    ADD_FOREIGN_KEY_NOT_VALID("ADD CONSTRAINT ... FOREIGN KEY ... NOT VALID", LockLevel.SHARE_ROW_EXCLUSIVE, LockLevel.SHARE_ROW_EXCLUSIVE, TableWork.NONE),
    VALIDATE_FOREIGN_KEY("VALIDATE CONSTRAINT on a FOREIGN KEY", LockLevel.SHARE_UPDATE_EXCLUSIVE, LockLevel.ROW_SHARE, TableWork.SCAN),
    ALTER_FOREIGN_KEY("ALTER CONSTRAINT", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    ADD_UNIQUE("ADD CONSTRAINT ... UNIQUE", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.SCAN),
    ADD_PRIMARY_KEY("ADD PRIMARY KEY", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.SCAN),
    ADD_PRIMARY_KEY_NULLABLE("ADD PRIMARY KEY on a nullable column", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.SCAN),
    ADD_CONSTRAINT_USING_INDEX("ADD CONSTRAINT ... UNIQUE USING INDEX", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    ADD_PRIMARY_KEY_USING_INDEX("ADD CONSTRAINT ... PRIMARY KEY USING INDEX", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    DROP_CONSTRAINT("DROP CONSTRAINT", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    DROP_FOREIGN_KEY("DROP CONSTRAINT on a FOREIGN KEY", LockLevel.ACCESS_EXCLUSIVE, LockLevel.ACCESS_EXCLUSIVE, TableWork.NONE),
    RENAME_CONSTRAINT("RENAME CONSTRAINT", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    CREATE_INDEX("CREATE INDEX", LockLevel.SHARE, null, TableWork.SCAN),
    DROP_INDEX("DROP INDEX", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    RENAME_INDEX("ALTER INDEX ... RENAME", null, null, TableWork.NONE),
    RENAME_COLUMN("RENAME COLUMN", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    RENAME_TABLE("ALTER TABLE ... RENAME", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    SET_SCHEMA("ALTER TABLE ... SET SCHEMA", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.NONE),
    SET_UNLOGGED("ALTER TABLE ... SET LOGGED or UNLOGGED", LockLevel.ACCESS_EXCLUSIVE, null, TableWork.REWRITE),
    DROP_TABLE("DROP TABLE", LockLevel.ACCESS_EXCLUSIVE, LockLevel.ACCESS_EXCLUSIVE, TableWork.NONE),
    CREATE_TABLE_WITH_FOREIGN_KEY("CREATE TABLE with a FOREIGN KEY", null, LockLevel.SHARE_ROW_EXCLUSIVE, TableWork.NONE),
    ADD_ENUM_VALUE("ALTER TYPE ... ADD VALUE", null, null, TableWork.NONE),
    RENAME_ENUM_VALUE("ALTER TYPE ... RENAME VALUE", null, null, TableWork.NONE),
    CREATE_INDEX_CONCURRENTLY("CREATE INDEX CONCURRENTLY", LockLevel.SHARE_UPDATE_EXCLUSIVE, null, TableWork.SCAN),
    DROP_INDEX_CONCURRENTLY("DROP INDEX CONCURRENTLY", LockLevel.SHARE_UPDATE_EXCLUSIVE, null, TableWork.NONE);

    private final String label;
    private final LockLevel lock;
    private final LockLevel referencedLock;
    private final TableWork work;

    Operation(String label, LockLevel lock, LockLevel referencedLock, TableWork work) {
        this.label = label;
        this.lock = lock;
        this.referencedLock = referencedLock;
        this.work = work;
    }

    /** The statement in short, for example {@code ADD CONSTRAINT ... FOREIGN KEY}. */
    public String label() {
        return label;
    }

    /** The strongest lock taken on the table being changed; empty if the table is not locked. */
    public Optional<LockLevel> lock() {
        return Optional.ofNullable(lock);
    }

    /**
     * The strongest lock taken on a table that the changed table references through a foreign
     * key; empty if that table is not locked.
     */
    public Optional<LockLevel> referencedLock() {
        return Optional.ofNullable(referencedLock);
    }

    /** The work done on the existing rows while the lock is held. */
    public TableWork work() {
        return work;
    }
}
