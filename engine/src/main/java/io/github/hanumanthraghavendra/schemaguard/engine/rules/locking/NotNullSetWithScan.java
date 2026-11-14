package io.github.hanumanthraghavendra.schemaguard.engine.rules.locking;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ChangeRule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import java.util.Optional;

/**
 * A column that becomes NOT NULL, unless the old schema already has a valid CHECK (column IS
 * NOT NULL), in which case PostgreSQL skips the scan.
 */
public final class NotNullSetWithScan extends ChangeRule<Change.ColumnNullabilityChanged> {

    public NotNullSetWithScan() {
        super(
                Change.ColumnNullabilityChanged.class,
                new RuleInfo(
                        "SG-R106",
                        "not-null-set-with-scan",
                        Category.LOCKING,
                        RiskLevel.HIGH,
                        "SET NOT NULL scans the table under a full lock",
                        "To accept NOT NULL, PostgreSQL has to be sure no row holds a NULL. It reads the "
                                + "whole table to find out, holding an ACCESS EXCLUSIVE lock that blocks reads and "
                                + "writes. If one NULL is found, the statement fails.",
                        "Add CHECK (column IS NOT NULL) NOT VALID, run VALIDATE CONSTRAINT, then SET NOT "
                                + "NULL: PostgreSQL 12 and later take the valid constraint as proof and skip the scan. "
                                + "Drop the constraint afterwards. Fill any NULLs before validating."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ColumnNullabilityChanged change, RuleContext context) {
        if (!change.notNull() || !context.mayHoldRows(change.table()) || context.provenNotNull(change.table(), change.column())) {
            return Optional.empty();
        }
        String message =
                "Making " + change.table() + "." + change.column() + " NOT NULL reads every row to look for NULLs while"
                        + " reads and writes wait. It fails if a NULL is found.";
        return found(change, message, Operation.SET_NOT_NULL);
    }
}
