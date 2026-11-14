package io.github.hanumanthraghavendra.schemaguard.engine.rules.locking;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.model.PrimaryKey;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ChangeRule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import java.util.Optional;

/**
 * A primary key added to a table that already has rows.
 */
public final class PrimaryKeyAdded extends ChangeRule<Change.ConstraintAdded> {

    public PrimaryKeyAdded() {
        super(
                Change.ConstraintAdded.class,
                new RuleInfo(
                        "SG-R107",
                        "primary-key-added",
                        Category.LOCKING,
                        RiskLevel.HIGH,
                        "Primary key is added by building its index under a full lock",
                        "ADD PRIMARY KEY builds a unique index and, if needed, checks the columns for NULLs. "
                                + "All of that happens under an ACCESS EXCLUSIVE lock, so reads and writes wait for the "
                                + "whole build. It fails if rows hold duplicates or NULLs.",
                        "Build the index first with CREATE UNIQUE INDEX CONCURRENTLY and make the columns NOT "
                                + "NULL the safe way. Then attach the index with ALTER TABLE ... ADD CONSTRAINT ... "
                                + "PRIMARY KEY USING INDEX, which only needs a brief lock."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ConstraintAdded change, RuleContext context) {
        if (!(change.constraint() instanceof PrimaryKey key) || !context.mayHoldRows(change.table())) {
            return Optional.empty();
        }
        String message =
                "Adding primary key " + key.name().orElse("(unnamed)") + " builds a unique index on " + change.table()
                        + " while reads and writes wait. It fails on duplicates or NULLs.";
        return found(change, message, Operation.ADD_PRIMARY_KEY);
    }
}
