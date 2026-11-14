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
 * A column that loses its default, is NOT NULL in the new schema and is neither an identity
 * nor a generated column.
 */
public final class DefaultDroppedOnRequiredColumn extends ChangeRule<Change.ColumnDefaultChanged> {

    public DefaultDroppedOnRequiredColumn() {
        super(
                Change.ColumnDefaultChanged.class,
                new RuleInfo(
                        "SG-R501",
                        "default-dropped-on-required-column",
                        Category.COMPATIBILITY,
                        RiskLevel.HIGH,
                        "Default is removed from a NOT NULL column",
                        "Application code that leaves the column out of its INSERT statements has relied on "
                                + "the default. Without it the column gets NULL, NOT NULL rejects that, and those "
                                + "inserts start to fail the moment the migration runs.",
                        "First release application code that always supplies a value for the column. Remove "
                                + "the default in a later migration."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ColumnDefaultChanged change, RuleContext context) {
        boolean required =
                context.tableAfter(change.table())
                        .flatMap(table -> table.column(change.column()))
                        .filter(c -> c.notNull() && c.identity() == Identity.NONE && c.generated().isEmpty())
                        .isPresent();
        if (change.before().isEmpty() || change.after().isPresent() || !required || !context.kept(change.table())) {
            return Optional.empty();
        }
        String message =
                change.table() + "." + change.column() + " is NOT NULL and loses its default (" + change.before().get()
                        + "). Every INSERT that leaves the column out starts to fail.";
        return found(change, message, Operation.DROP_DEFAULT);
    }
}
