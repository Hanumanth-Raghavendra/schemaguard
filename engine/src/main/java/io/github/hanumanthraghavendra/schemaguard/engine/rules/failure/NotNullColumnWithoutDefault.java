package io.github.hanumanthraghavendra.schemaguard.engine.rules.failure;

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
import java.util.Optional;

/**
 * A NOT NULL column without a default, identity or generation expression, added to a table
 * that already has rows.
 */
public final class NotNullColumnWithoutDefault extends ChangeRule<Change.ColumnAdded> {

    public NotNullColumnWithoutDefault() {
        super(
                Change.ColumnAdded.class,
                new RuleInfo(
                        "SG-R401",
                        "not-null-column-without-default",
                        Category.FAILURE,
                        RiskLevel.HIGH,
                        "New NOT NULL column has no default",
                        "PostgreSQL has to put something into the new column of every existing row. Without a "
                                + "default that something is NULL, which NOT NULL forbids, so the statement fails as "
                                + "soon as the table has one row. It works on an empty test database and fails in "
                                + "production.",
                        "Give the column a constant default: since PostgreSQL 11 that does not rewrite the "
                                + "table. Or add it nullable, fill the rows in batches, and set NOT NULL the safe way "
                                + "afterwards."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ColumnAdded change, RuleContext context) {
        Column column = change.column();
        boolean hasValue =
                column.defaultValue().isPresent() || column.identity() != Identity.NONE || column.generated().isPresent();
        if (!column.notNull() || hasValue || !context.mayHoldRows(change.table())) {
            return Optional.empty();
        }
        String message =
                "New column " + change.table() + "." + column.name() + " is NOT NULL and has no default. The statement fails"
                        + " if the table has any rows.";
        return found(change, message, Operation.ADD_COLUMN);
    }
}
