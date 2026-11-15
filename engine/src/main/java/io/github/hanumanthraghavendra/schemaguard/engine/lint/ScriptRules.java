package io.github.hanumanthraghavendra.schemaguard.engine.lint;

import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import java.util.List;

/**
 * Rules that only a script can break. Two schemas do not show a rename or a transaction
 * block; the statements of a migration do.
 */
public final class ScriptRules {

    public static final RuleInfo CONCURRENTLY_IN_TRANSACTION =
            new RuleInfo(
                    "SG-R403",
                    "concurrently-in-transaction",
                    Category.FAILURE,
                    RiskLevel.HIGH,
                    "CONCURRENTLY is used inside a transaction block",
                    "CREATE INDEX CONCURRENTLY and DROP INDEX CONCURRENTLY work in several transactions of their own, so"
                            + " PostgreSQL refuses them inside BEGIN ... COMMIT. The script stops at that statement."
                            + " Migration tools that wrap every file in a transaction cause the same failure.",
                    "Move the statement out of the transaction block, into a migration of its own, and switch off the"
                            + " tool's automatic transaction for that file.");

    public static final RuleInfo RENAMED_IN_PLACE =
            new RuleInfo(
                    "SG-R510",
                    "renamed-in-place",
                    Category.COMPATIBILITY,
                    RiskLevel.HIGH,
                    "Table, column or enum value is renamed in one step",
                    "The rename itself is quick and keeps the data. But the old name is gone the moment it commits, and"
                            + " every query of the running application that still uses it fails. Application and database"
                            + " cannot be deployed at the same instant, so there is always a window where one of them is"
                            + " wrong.",
                    "Add the new name next to the old one: a new column kept in step by a trigger, or a view under the"
                            + " new table name. Move the application over, then remove the old name in a later migration.");

    /** Every script rule, ordered by id. */
    public static final List<RuleInfo> ALL = List.of(CONCURRENTLY_IN_TRANSACTION, RENAMED_IN_PLACE);

    private ScriptRules() {}
}
