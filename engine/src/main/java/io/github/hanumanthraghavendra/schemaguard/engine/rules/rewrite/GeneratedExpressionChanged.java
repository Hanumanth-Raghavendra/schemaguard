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
 * A column that gets a generation expression or a different one. Removing the expression is a
 * quick catalog change and is not reported.
 */
public final class GeneratedExpressionChanged extends ChangeRule<Change.ColumnGeneratedChanged> {

    public GeneratedExpressionChanged() {
        super(
                Change.ColumnGeneratedChanged.class,
                new RuleInfo(
                        "SG-R206",
                        "generated-expression-changed",
                        Category.REWRITE,
                        RiskLevel.HIGH,
                        "Generated column changes its expression, so the column is rebuilt",
                        "PostgreSQL 16 cannot change the expression of a generated column, nor turn a plain "
                                + "column into a generated one. The column has to be dropped and added again, and "
                                + "adding it computes the value for every row: a full table rewrite under an ACCESS "
                                + "EXCLUSIVE lock. A plain column that becomes generated loses its stored values.",
                        "Add a second column with the new expression the safe way (plain column, filled in "
                                + "batches, kept current by a trigger), move the readers over, then drop the old "
                                + "column."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ColumnGeneratedChanged change, RuleContext context) {
        if (change.after().isEmpty() || !context.kept(change.table())) {
            return Optional.empty();
        }
        String message =
                change.before().isPresent()
                        ? "The expression of generated column " + change.table() + "." + change.column()
                                + " changes. The column is dropped and added again, which rewrites the whole table."
                        : "Column " + change.table() + "." + change.column()
                                + " becomes a generated column. It is dropped and added again: its stored values are"
                                + " discarded and the whole table is rewritten.";
        return found(change, message, Operation.ADD_COLUMN_GENERATED);
    }
}
