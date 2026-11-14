package io.github.hanumanthraghavendra.schemaguard.engine.rules.rewrite;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ChangeRule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.TypeChange;
import java.util.Optional;

/**
 * A type change that PostgreSQL cannot do by updating the catalog alone. Which changes those
 * are was measured on a real server.
 */
public final class ColumnTypeRewrite extends ChangeRule<Change.ColumnTypeChanged> {

    public ColumnTypeRewrite() {
        super(
                Change.ColumnTypeChanged.class,
                new RuleInfo(
                        "SG-R201",
                        "column-type-rewrite",
                        Category.REWRITE,
                        RiskLevel.HIGH,
                        "Column type change rewrites the table",
                        "The stored bytes of the old type are not valid for the new one. PostgreSQL writes "
                                + "every row into a new copy of the table and rebuilds every index, all under an ACCESS "
                                + "EXCLUSIVE lock: no reads, no writes, for as long as it takes. It also needs free "
                                + "disk space for the second copy.",
                        "Add a new column of the new type, fill it in batches, keep it in step with a "
                                + "trigger, then switch the application over and drop the old column. Some changes need "
                                + "no rewrite at all: a longer varchar, varchar to text, or a numeric with more digits "
                                + "and the same scale."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ColumnTypeChanged change, RuleContext context) {
        if (!context.kept(change.table()) || !new TypeChange(change.before(), change.after()).rewrites()) {
            return Optional.empty();
        }
        String message =
                "Changing " + change.table() + "." + change.column() + " from " + change.before().toSql() + " to "
                        + change.after().toSql() + " rewrites the whole table while reads and writes wait.";
        return found(change, message, Operation.ALTER_TYPE_REWRITE);
    }
}
