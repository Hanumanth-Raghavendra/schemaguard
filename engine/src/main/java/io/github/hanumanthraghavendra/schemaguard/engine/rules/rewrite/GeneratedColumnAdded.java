package io.github.hanumanthraghavendra.schemaguard.engine.rules.rewrite;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Column;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ChangeRule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import java.util.Optional;

/**
 * A generated column added to a table that already has rows.
 */
public final class GeneratedColumnAdded extends ChangeRule<Change.ColumnAdded> {

    public GeneratedColumnAdded() {
        super(
                Change.ColumnAdded.class,
                new RuleInfo(
                        "SG-R205",
                        "generated-column-added",
                        Category.REWRITE,
                        RiskLevel.HIGH,
                        "New stored generated column rewrites the table",
                        "A stored generated column keeps its computed value in each row, so PostgreSQL "
                                + "computes it for every existing row. PostgreSQL writes every row into a new copy of "
                                + "the table and rebuilds every index, all under an ACCESS EXCLUSIVE lock: no reads, no "
                                + "writes, for as long as it takes. It also needs free disk space for the second copy.",
                        "Add a plain column, fill it in batches and keep it current with a trigger. If the "
                                + "value is only read now and then, compute it in the query or in a view instead."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ColumnAdded change, RuleContext context) {
        Column column = change.column();
        if (column.generated().isEmpty() || !context.mayHoldRows(change.table())) {
            return Optional.empty();
        }
        String message =
                "New generated column " + change.table() + "." + column.name()
                        + " is computed for every existing row, so the whole table is rewritten while reads and writes wait.";
        return found(change, message, Operation.ADD_COLUMN_GENERATED);
    }
}
