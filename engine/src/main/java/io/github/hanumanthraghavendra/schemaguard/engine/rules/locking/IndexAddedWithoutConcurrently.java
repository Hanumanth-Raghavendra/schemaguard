package io.github.hanumanthraghavendra.schemaguard.engine.rules.locking;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Index;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ChangeRule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import java.util.Optional;

/**
 * An index added to a table that already has rows. An index on a table created in the same
 * migration is built on an empty table and is not reported.
 */
public final class IndexAddedWithoutConcurrently extends ChangeRule<Change.IndexAdded> {

    public IndexAddedWithoutConcurrently() {
        super(
                Change.IndexAdded.class,
                new RuleInfo(
                        "SG-R101",
                        "index-added-without-concurrently",
                        Category.LOCKING,
                        RiskLevel.HIGH,
                        "Index is built without CONCURRENTLY",
                        "CREATE INDEX takes a SHARE lock on the table for the whole build. Reads continue, "
                                + "but every INSERT, UPDATE and DELETE waits, and on a large table the build takes "
                                + "minutes or hours.",
                        "Use CREATE INDEX CONCURRENTLY. It builds the index without blocking writes. It "
                                + "cannot run inside a transaction, and if it fails it leaves an invalid index that has "
                                + "to be dropped before trying again."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.IndexAdded change, RuleContext context) {
        Index index = change.index();
        if (!context.mayHoldRows(index.table())) {
            return Optional.empty();
        }
        String message =
                "Building index " + index.name().orElse("(unnamed)") + " blocks every write to " + index.table()
                        + " until the build ends."
                        + (index.unique() ? " The build fails if existing rows hold duplicate values." : "");
        return found(change, message, Operation.CREATE_INDEX);
    }
}
