package io.github.hanumanthraghavendra.schemaguard.engine.rules.locking;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Unique;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ChangeRule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import java.util.Optional;

/**
 * A UNIQUE constraint added to a table that already has rows.
 */
public final class UniqueAdded extends ChangeRule<Change.ConstraintAdded> {

    public UniqueAdded() {
        super(
                Change.ConstraintAdded.class,
                new RuleInfo(
                        "SG-R108",
                        "unique-added",
                        Category.LOCKING,
                        RiskLevel.HIGH,
                        "UNIQUE constraint is added by building its index under a full lock",
                        "ADD CONSTRAINT ... UNIQUE builds a unique index under an ACCESS EXCLUSIVE lock, so "
                                + "reads and writes wait for the whole build. It fails if existing rows hold duplicate "
                                + "values.",
                        "Build the index first with CREATE UNIQUE INDEX CONCURRENTLY, then attach it with "
                                + "ALTER TABLE ... ADD CONSTRAINT ... UNIQUE USING INDEX, which only needs a brief "
                                + "lock. Look for duplicates before starting."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ConstraintAdded change, RuleContext context) {
        if (!(change.constraint() instanceof Unique key) || !context.mayHoldRows(change.table())) {
            return Optional.empty();
        }
        String message =
                "Adding unique constraint " + key.name().orElse("(unnamed)") + " builds a unique index on " + change.table()
                        + " while reads and writes wait. It fails if rows hold duplicates.";
        return found(change, message, Operation.ADD_UNIQUE);
    }
}
