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
 * An index whose definition differs between the two schemas, beyond parentheses and casts.
 */
public final class IndexReplaced extends ChangeRule<Change.IndexChanged> {

    public IndexReplaced() {
        super(
                Change.IndexChanged.class,
                new RuleInfo(
                        "SG-R103",
                        "index-replaced",
                        Category.LOCKING,
                        RiskLevel.HIGH,
                        "Index definition changes, so the index is rebuilt",
                        "PostgreSQL cannot change the columns, the method or the WHERE clause of an existing "
                                + "index. The direct route drops the index and builds it again: writes are blocked "
                                + "during the build, and queries have no index in between.",
                        "Build the new definition under a temporary name with CREATE INDEX CONCURRENTLY, drop "
                                + "the old index with DROP INDEX CONCURRENTLY, then rename the new one with ALTER INDEX "
                                + "... RENAME."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.IndexChanged change, RuleContext context) {
        Index index = change.after();
        if (!context.mayHoldRows(index.table())) {
            return Optional.empty();
        }
        String message =
                "Index " + index.name().orElse("(unnamed)") + " has a new definition. Dropping and rebuilding it blocks writes to "
                        + index.table() + " during the build and leaves queries without the index in between.";
        return found(change, message, Operation.CREATE_INDEX);
    }
}
