package io.github.hanumanthraghavendra.schemaguard.engine.rules;

/**
 * How much work a statement does on the rows a table already holds. The lock is held for as
 * long as this work takes, so it decides whether a lock lasts milliseconds or hours.
 */
public enum TableWork {
    /** Only the system catalogs change. The time does not depend on the size of the table. */
    NONE,
    /** Every row is read once, for example to check a new constraint or to build an index. */
    SCAN,
    /** Every row is written again into a new copy of the table, and every index is rebuilt. */
    REWRITE;

    /** True if the time the statement takes grows with the number of rows. */
    public boolean growsWithTable() {
        return this != NONE;
    }
}
