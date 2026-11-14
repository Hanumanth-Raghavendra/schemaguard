package io.github.hanumanthraghavendra.schemaguard.engine.rules.failure;

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
 * A type change between types of different families, such as text to integer or timestamp to
 * timestamptz.
 */
public final class ColumnTypeConversion extends ChangeRule<Change.ColumnTypeChanged> {

    public ColumnTypeConversion() {
        super(
                Change.ColumnTypeChanged.class,
                new RuleInfo(
                        "SG-R402",
                        "column-type-conversion",
                        Category.FAILURE,
                        RiskLevel.MEDIUM,
                        "Column changes to a different kind of type",
                        "PostgreSQL has to convert every value, and usually needs a USING expression that "
                                + "says how. One value that cannot be converted, such as a text that is not a number, "
                                + "aborts the whole migration after it may have run for a long time.",
                        "Test the conversion on a copy of the production data. To find the bad values first, "
                                + "run the USING expression in a SELECT. For large tables, add a new column, convert in "
                                + "batches and swap."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ColumnTypeChanged change, RuleContext context) {
        if (!context.kept(change.table())
                || new TypeChange(change.before(), change.after()).kind() != TypeChange.Kind.CONVERSION) {
            return Optional.empty();
        }
        String message =
                "Every value of " + change.table() + "." + change.column() + " has to be converted from "
                        + change.before().toSql() + " to " + change.after().toSql()
                        + ". One value that cannot be converted makes the migration fail.";
        return found(change, message);
    }
}
