package io.github.hanumanthraghavendra.schemaguard.engine.rules.rewrite;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ChangeRule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import java.util.Optional;

/**
 * A table whose columns are in a different order in the new schema.
 */
public final class ColumnOrderChanged extends ChangeRule<Change.ColumnOrderChanged> {

    public ColumnOrderChanged() {
        super(
                Change.ColumnOrderChanged.class,
                new RuleInfo(
                        "SG-R210",
                        "column-order-changed",
                        Category.REWRITE,
                        RiskLevel.LOW,
                        "Column order cannot be changed in place",
                        "PostgreSQL always adds a new column at the end and has no statement to move one. "
                                + "Reaching exactly the column order of the new schema means creating the table again "
                                + "and copying every row, which is rarely worth it.",
                        "Accept the order PostgreSQL gives. Column order has no effect on queries that name "
                                + "their columns. To keep the schema file and the database the same, list new columns "
                                + "last in the file."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ColumnOrderChanged change, RuleContext context) {
        if (!context.kept(change.table())) {
            return Optional.empty();
        }
        String message =
                "The columns of " + change.table() + " are in a different order in the new schema. Only creating the table"
                        + " again can produce that order.";
        return found(change, message);
    }
}
