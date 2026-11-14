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
import java.util.Optional;

/**
 * An identity column added to a table that already has rows.
 */
public final class IdentityColumnAdded extends ChangeRule<Change.ColumnAdded> {

    public IdentityColumnAdded() {
        super(
                Change.ColumnAdded.class,
                new RuleInfo(
                        "SG-R204",
                        "identity-column-added",
                        Category.REWRITE,
                        RiskLevel.HIGH,
                        "New identity column rewrites the table",
                        "An identity column needs a number in every row, so PostgreSQL draws one from the "
                                + "sequence for each existing row. PostgreSQL writes every row into a new copy of the "
                                + "table and rebuilds every index, all under an ACCESS EXCLUSIVE lock: no reads, no "
                                + "writes, for as long as it takes. It also needs free disk space for the second copy.",
                        "Add a plain nullable column, fill it in batches from a sequence, make it NOT NULL "
                                + "the safe way, then attach the identity with ALTER COLUMN ... ADD GENERATED ... AS "
                                + "IDENTITY, which does not rewrite. Start the identity above the highest number used."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ColumnAdded change, RuleContext context) {
        Column column = change.column();
        if (column.identity() == Identity.NONE || !context.mayHoldRows(change.table())) {
            return Optional.empty();
        }
        String message =
                "New identity column " + change.table() + "." + column.name()
                        + " needs a value in every existing row, so the whole table is rewritten while reads and writes wait.";
        return found(change, message, Operation.ADD_COLUMN_IDENTITY);
    }
}
