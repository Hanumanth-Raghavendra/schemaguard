package io.github.hanumanthraghavendra.schemaguard.engine.rules.locking;

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
 * A collation change on a column that an index or a key covers. Without an index the change
 * only touches the catalog and is not reported.
 */
public final class CollationChangedOnIndexedColumn extends ChangeRule<Change.ColumnCollationChanged> {

    public CollationChangedOnIndexedColumn() {
        super(
                Change.ColumnCollationChanged.class,
                new RuleInfo(
                        "SG-R110",
                        "collation-changed-on-indexed-column",
                        Category.LOCKING,
                        RiskLevel.HIGH,
                        "Collation change rebuilds the indexes on the column",
                        "A collation decides how text sorts, so an index sorted by the old collation is "
                                + "useless for the new one. PostgreSQL rebuilds every index on the column, reading the "
                                + "whole table under an ACCESS EXCLUSIVE lock that blocks reads and writes.",
                        "If only some queries need the other collation, leave the column alone and write "
                                + "COLLATE in those queries, or add a second index with the new collation using CREATE "
                                + "INDEX CONCURRENTLY."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ColumnCollationChanged change, RuleContext context) {
        if (!context.kept(change.table()) || !context.indexed(change.table(), change.column())) {
            return Optional.empty();
        }
        String message =
                "Changing the collation of " + change.table() + "." + change.column()
                        + " rebuilds its indexes while reads and writes wait.";
        return found(change, message, Operation.ALTER_COLLATION_INDEXED);
    }
}
