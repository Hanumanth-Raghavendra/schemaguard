package io.github.hanumanthraghavendra.schemaguard.engine.rules.rewrite;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Column;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Identity;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ChangeRule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Volatility;
import java.util.Optional;

/**
 * A column with a volatile default added to a table that already has rows. This includes
 * serial columns.
 */
public final class ColumnAddedWithVolatileDefault extends ChangeRule<Change.ColumnAdded> {

    public ColumnAddedWithVolatileDefault() {
        super(
                Change.ColumnAdded.class,
                new RuleInfo(
                        "SG-R202",
                        "column-added-with-volatile-default",
                        Category.REWRITE,
                        RiskLevel.HIGH,
                        "New column has a volatile default, which rewrites the table",
                        "A constant default is stored once in the catalog. A volatile default, such as "
                                + "random(), gen_random_uuid() or the nextval() behind serial, gives a different value "
                                + "for each row, so each existing row has to be written. PostgreSQL writes every row "
                                + "into a new copy of the table and rebuilds every index, all under an ACCESS EXCLUSIVE "
                                + "lock: no reads, no writes, for as long as it takes. It also needs free disk space "
                                + "for the second copy.",
                        "Add the column without a default, then set the default with ALTER COLUMN ... SET "
                                + "DEFAULT, which only affects new rows. Fill the existing rows in batches."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ColumnAdded change, RuleContext context) {
        Column column = change.column();
        boolean plain = column.identity() == Identity.NONE && column.generated().isEmpty();
        boolean isVolatile = column.defaultValue().map(Volatility::of).filter(Volatility.VOLATILE::equals).isPresent();
        if (!plain || !isVolatile || !context.mayHoldRows(change.table())) {
            return Optional.empty();
        }
        String message =
                "The default of new column " + change.table() + "." + column.name() + " (" + column.defaultValue().get()
                        + ") is volatile, so every existing row is written again while reads and writes wait.";
        return found(change, message, Operation.ADD_COLUMN_VOLATILE_DEFAULT);
    }
}
