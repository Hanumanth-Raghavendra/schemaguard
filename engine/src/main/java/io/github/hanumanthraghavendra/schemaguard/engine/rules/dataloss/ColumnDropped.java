package io.github.hanumanthraghavendra.schemaguard.engine.rules.dataloss;

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
 * A column dropped from a table that stays.
 */
public final class ColumnDropped extends ChangeRule<Change.ColumnDropped> {

    public ColumnDropped() {
        super(
                Change.ColumnDropped.class,
                new RuleInfo(
                        "SG-R302",
                        "column-dropped",
                        Category.DATA_LOSS,
                        RiskLevel.CRITICAL,
                        "Column is dropped with all its values",
                        "DROP COLUMN makes the values of the column unreachable in every row, and they cannot "
                                + "be brought back without a backup. Application code that still selects or inserts the "
                                + "column fails at once, including code running during the deployment.",
                        "First release application code that no longer reads or writes the column, then drop "
                                + "it in a later migration. Keep a backup of the values until the change has proven "
                                + "itself."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ColumnDropped change, RuleContext context) {
        if (!context.kept(change.table())) {
            return Optional.empty();
        }
        String message = "Dropping " + change.table() + "." + change.column().name() + " deletes its values in every row for good.";
        return found(change, message, Operation.DROP_COLUMN);
    }
}
