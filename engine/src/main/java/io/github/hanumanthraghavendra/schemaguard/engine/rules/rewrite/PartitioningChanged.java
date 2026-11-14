package io.github.hanumanthraghavendra.schemaguard.engine.rules.rewrite;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ChangeRule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import java.util.Optional;

/**
 * A table whose PARTITION BY clause is added, removed or different.
 */
public final class PartitioningChanged extends ChangeRule<Change.TablePartitioningChanged> {

    public PartitioningChanged() {
        super(
                Change.TablePartitioningChanged.class,
                new RuleInfo(
                        "SG-R208",
                        "partitioning-changed",
                        Category.REWRITE,
                        RiskLevel.HIGH,
                        "Partitioning cannot be changed in place",
                        "PostgreSQL decides how a table is partitioned when the table is created and offers "
                                + "no statement to change it later. Reaching the new schema means creating a new table "
                                + "and copying every row into it.",
                        "Create the partitioned table under a new name, copy the rows in batches while a "
                                + "trigger keeps both in step, then swap the names in one short transaction. A plain "
                                + "table can also be attached as the first partition of a new partitioned table without "
                                + "copying."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.TablePartitioningChanged change, RuleContext context) {
        if (!context.kept(change.table())) {
            return Optional.empty();
        }
        String message =
                "The partitioning of " + change.table() + " changes from " + change.before().orElse("none") + " to "
                        + change.after().orElse("none") + ". The table has to be created again and every row copied.";
        return found(change, message);
    }
}
