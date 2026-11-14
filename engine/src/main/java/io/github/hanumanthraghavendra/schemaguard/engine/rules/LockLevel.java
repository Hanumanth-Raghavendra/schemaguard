package io.github.hanumanthraghavendra.schemaguard.engine.rules;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * PostgreSQL's eight table lock modes, weakest first.
 *
 * <p>The names say little about what a mode does. What matters is which other modes it
 * conflicts with: a plain {@code SELECT} takes {@link #ACCESS_SHARE}, and {@code INSERT},
 * {@code UPDATE} and {@code DELETE} take {@link #ROW_EXCLUSIVE}. A schema change blocks reads
 * or writes exactly when its lock conflicts with those two.
 */
public enum LockLevel {
    ACCESS_SHARE,
    ROW_SHARE,
    ROW_EXCLUSIVE,
    SHARE_UPDATE_EXCLUSIVE,
    SHARE,
    SHARE_ROW_EXCLUSIVE,
    EXCLUSIVE,
    ACCESS_EXCLUSIVE;

    /**
     * One row per held mode, one column per requested mode, both in declaration order;
     * {@code X} marks a conflict. Checked against a running server by a test.
     */
    private static final String[] CONFLICTS = {
        ".......X",
        "......XX",
        "....XXXX",
        "...XXXXX",
        "..XX.XXX",
        "..XXXXXX",
        ".XXXXXXX",
        "XXXXXXXX",
    };

    /** True if a session cannot take {@code other} while another session holds this mode. */
    public boolean conflictsWith(LockLevel other) {
        return CONFLICTS[ordinal()].charAt(other.ordinal()) == 'X';
    }

    /** True if queries that only read the table have to wait. */
    public boolean blocksReads() {
        return conflictsWith(ACCESS_SHARE);
    }

    /** True if {@code INSERT}, {@code UPDATE} and {@code DELETE} have to wait. */
    public boolean blocksWrites() {
        return conflictsWith(ROW_EXCLUSIVE);
    }

    /** What the application notices while the lock is held, as a short phrase. */
    public String effect() {
        if (blocksReads()) {
            return "blocks reads and writes";
        }
        if (blocksWrites()) {
            return "blocks writes, reads continue";
        }
        if (conflictsWith(SHARE_UPDATE_EXCLUSIVE)) {
            return "reads and writes continue, other schema changes wait";
        }
        return "reads and writes continue";
    }

    /** The mode as written in SQL, for example {@code ACCESS EXCLUSIVE}. */
    public String sqlName() {
        return name().replace('_', ' ');
    }

    /** The mode as the {@code pg_locks} view shows it, for example {@code AccessExclusiveLock}. */
    public String catalogName() {
        StringBuilder out = new StringBuilder();
        for (String word : name().split("_")) {
            out.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.append("Lock").toString();
    }

    public static Optional<LockLevel> fromSqlName(String sqlName) {
        String wanted = sqlName.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(level -> level.sqlName().equals(wanted)).findFirst();
    }

    public static Optional<LockLevel> fromCatalogName(String catalogName) {
        return Arrays.stream(values()).filter(level -> level.catalogName().equals(catalogName)).findFirst();
    }
}
