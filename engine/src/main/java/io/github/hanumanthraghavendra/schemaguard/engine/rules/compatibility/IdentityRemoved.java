package io.github.hanumanthraghavendra.schemaguard.engine.rules.compatibility;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Column;
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
 * A column that was an identity column and now is a plain column without a default.
 */
public final class IdentityRemoved extends ChangeRule<Change.ColumnIdentityChanged> {

    public IdentityRemoved() {
        super(
                Change.ColumnIdentityChanged.class,
                new RuleInfo(
                        "SG-R506",
                        "identity-removed",
                        Category.COMPATIBILITY,
                        RiskLevel.MEDIUM,
                        "Identity is removed and nothing generates the value any more",
                        "Inserts have relied on the database to fill the column. After DROP IDENTITY the "
                                + "column has no source of values: an INSERT that leaves it out gets NULL, and as "
                                + "identity columns are NOT NULL, the insert fails.",
                        "Give the column a default in the same migration, for example nextval of a sequence "
                                + "that continues from the last value, or first release application code that supplies "
                                + "the value itself."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ColumnIdentityChanged change, RuleContext context) {
        boolean hasDefault =
                context.tableAfter(change.table())
                        .flatMap(table -> table.column(change.column()))
                        .flatMap(Column::defaultValue)
                        .isPresent();
        if (change.before() == Identity.NONE || change.after() != Identity.NONE || hasDefault || !context.kept(change.table())) {
            return Optional.empty();
        }
        String message =
                change.table() + "." + change.column() + " is no longer an identity column and has no default. Inserts that"
                        + " relied on the generated value start to fail.";
        return found(change, message, Operation.DROP_IDENTITY);
    }
}
