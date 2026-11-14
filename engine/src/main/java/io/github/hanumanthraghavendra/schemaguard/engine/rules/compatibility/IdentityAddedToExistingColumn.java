package io.github.hanumanthraghavendra.schemaguard.engine.rules.compatibility;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
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
 * A column that was not an identity column and now is.
 */
public final class IdentityAddedToExistingColumn extends ChangeRule<Change.ColumnIdentityChanged> {

    public IdentityAddedToExistingColumn() {
        super(
                Change.ColumnIdentityChanged.class,
                new RuleInfo(
                        "SG-R505",
                        "identity-added-to-existing-column",
                        Category.COMPATIBILITY,
                        RiskLevel.HIGH,
                        "Identity added to a column that already has values starts at 1",
                        "ADD GENERATED ... AS IDENTITY creates a new sequence that starts at 1. The column "
                                + "already holds numbers, so the first inserts get values that are taken and fail with "
                                + "a duplicate key error. This is true even when the column was a serial before: the "
                                + "identity does not take over the old sequence.",
                        "Start the identity above the highest value in use: ADD GENERATED ... AS IDENTITY "
                                + "(START WITH n), or run ALTER COLUMN ... RESTART WITH n in the same transaction."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ColumnIdentityChanged change, RuleContext context) {
        if (change.before() != Identity.NONE || change.after() == Identity.NONE || !context.kept(change.table())) {
            return Optional.empty();
        }
        String message =
                change.table() + "." + change.column() + " becomes an identity column. Its new sequence starts at 1 and"
                        + " collides with the values already in the table.";
        return found(change, message, Operation.ADD_IDENTITY);
    }
}
