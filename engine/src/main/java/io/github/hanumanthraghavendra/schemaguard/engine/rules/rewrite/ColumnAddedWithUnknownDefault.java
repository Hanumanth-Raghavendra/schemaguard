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
 * A column whose default calls a function of unknown volatility, added to a table that already
 * has rows. The finding counts half, as it rests on a guess.
 */
public final class ColumnAddedWithUnknownDefault extends ChangeRule<Change.ColumnAdded> {

    public ColumnAddedWithUnknownDefault() {
        super(
                Change.ColumnAdded.class,
                new RuleInfo(
                        "SG-R203",
                        "column-added-with-unknown-default",
                        Category.REWRITE,
                        RiskLevel.MEDIUM,
                        "New column has a default that may rewrite the table",
                        "The default calls a function that is not built into PostgreSQL, so SchemaGuard "
                                + "cannot tell whether it is volatile. If it is, every existing row has to be written "
                                + "again under an ACCESS EXCLUSIVE lock.",
                        "Check the function with SELECT provolatile FROM pg_proc WHERE proname = '...'. If "
                                + "the answer is v, add the column without a default, set the default afterwards and "
                                + "fill the rows in batches."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ColumnAdded change, RuleContext context) {
        Column column = change.column();
        boolean plain = column.identity() == Identity.NONE && column.generated().isEmpty();
        boolean unknown = column.defaultValue().map(Volatility::of).filter(Volatility.UNKNOWN::equals).isPresent();
        if (!plain || !unknown || !context.mayHoldRows(change.table())) {
            return Optional.empty();
        }
        String message =
                "The default of new column " + change.table() + "." + column.name() + " (" + column.defaultValue().get()
                        + ") calls a function SchemaGuard does not know. If it is volatile, the table is rewritten.";
        return found(change, message, Operation.ADD_COLUMN_VOLATILE_DEFAULT).map(finding -> finding.withCertainty(0.5));
    }
}
