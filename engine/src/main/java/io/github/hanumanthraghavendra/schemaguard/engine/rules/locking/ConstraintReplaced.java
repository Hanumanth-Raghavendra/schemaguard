package io.github.hanumanthraghavendra.schemaguard.engine.rules.locking;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Check;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Constraint;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ForeignKey;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ChangeRule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.DirectRoute;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import java.util.Optional;

/**
 * A constraint whose definition differs between the two schemas, beyond parentheses and casts.
 * A new definition marked NOT VALID is not reported: adding it checks no rows.
 */
public final class ConstraintReplaced extends ChangeRule<Change.ConstraintChanged> {

    public ConstraintReplaced() {
        super(
                Change.ConstraintChanged.class,
                new RuleInfo(
                        "SG-R109",
                        "constraint-replaced",
                        Category.LOCKING,
                        RiskLevel.HIGH,
                        "Constraint definition changes, so the constraint is dropped and added again",
                        "PostgreSQL cannot alter what a constraint checks. The direct route drops it and adds "
                                + "the new definition, which checks every row again under a lock. If the two statements "
                                + "are not in one transaction, invalid rows can slip in between them.",
                        "Add the new definition under a new name with NOT VALID, run VALIDATE CONSTRAINT, and "
                                + "only then drop the old constraint. The table is never without the rule and never "
                                + "locked for long. For a key, build the new unique index concurrently first."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ConstraintChanged change, RuleContext context) {
        Constraint after = change.after();
        boolean checksNoRows =
                (after instanceof Check check && check.notValid()) || (after instanceof ForeignKey key && key.notValid());
        if (checksNoRows || !context.mayHoldRows(change.table())) {
            return Optional.empty();
        }
        String message =
                "Constraint " + after.name().orElse("(unnamed)") + " on " + change.table()
                        + " has a new definition. Dropping it and adding it again checks every row under a lock.";
        return DirectRoute.of(change, context).flatMap(operation -> found(change, message, operation));
    }
}
