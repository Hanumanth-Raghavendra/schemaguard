package io.github.hanumanthraghavendra.schemaguard.engine.rules.compatibility;

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
 * A column that was NOT NULL and no longer is.
 */
public final class NotNullDropped extends ChangeRule<Change.ColumnNullabilityChanged> {

    public NotNullDropped() {
        super(
                Change.ColumnNullabilityChanged.class,
                new RuleInfo(
                        "SG-R502",
                        "not-null-dropped",
                        Category.COMPATIBILITY,
                        RiskLevel.LOW,
                        "Column starts to accept NULL",
                        "The database change is quick and safe. The risk is in the code that reads the "
                                + "column: application code, reports and exports written when the column could not be "
                                + "NULL may fail or show wrong results on the first NULL.",
                        "Check the readers of the column before anything writes a NULL into it."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ColumnNullabilityChanged change, RuleContext context) {
        if (change.notNull() || !context.kept(change.table())) {
            return Optional.empty();
        }
        String message =
                change.table() + "." + change.column() + " may now hold NULL. Code that reads it has to be ready for that.";
        return found(change, message, Operation.DROP_NOT_NULL);
    }
}
