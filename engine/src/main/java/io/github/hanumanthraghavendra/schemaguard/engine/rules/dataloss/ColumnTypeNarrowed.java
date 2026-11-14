package io.github.hanumanthraghavendra.schemaguard.engine.rules.dataloss;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ChangeRule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.TypeChange;
import java.util.Optional;

/**
 * A type change to a shorter text, a smaller number type, fewer digits, or less precision.
 */
public final class ColumnTypeNarrowed extends ChangeRule<Change.ColumnTypeChanged> {

    public ColumnTypeNarrowed() {
        super(
                Change.ColumnTypeChanged.class,
                new RuleInfo(
                        "SG-R304",
                        "column-type-narrowed",
                        Category.DATA_LOSS,
                        RiskLevel.HIGH,
                        "New column type holds less than the old one",
                        "A value that does not fit the new type makes the migration fail half way: a text "
                                + "longer than the new limit, a number larger than the new range. Some values are "
                                + "changed without any error: numbers are rounded to the new scale and timestamps to "
                                + "the new precision.",
                        "Look at the existing values first, for example max(length(column)) or max(column). "
                                + "To be safe, add a CHECK constraint with the new limit as NOT VALID and validate it: "
                                + "once it holds, the type change cannot fail."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ColumnTypeChanged change, RuleContext context) {
        if (!context.kept(change.table())
                || new TypeChange(change.before(), change.after()).kind() != TypeChange.Kind.NARROWING) {
            return Optional.empty();
        }
        String message =
                change.after().toSql() + " holds less than " + change.before().toSql() + ". Values of " + change.table() + "."
                        + change.column() + " that do not fit make the migration fail, or are rounded.";
        return found(change, message);
    }
}
