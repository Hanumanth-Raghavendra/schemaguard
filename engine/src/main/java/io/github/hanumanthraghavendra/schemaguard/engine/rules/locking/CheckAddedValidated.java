package io.github.hanumanthraghavendra.schemaguard.engine.rules.locking;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Check;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ChangeRule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import java.util.Optional;

/**
 * A CHECK constraint, not marked NOT VALID, added to a table that already has rows.
 */
public final class CheckAddedValidated extends ChangeRule<Change.ConstraintAdded> {

    public CheckAddedValidated() {
        super(
                Change.ConstraintAdded.class,
                new RuleInfo(
                        "SG-R105",
                        "check-added-validated",
                        Category.LOCKING,
                        RiskLevel.HIGH,
                        "CHECK constraint is added without NOT VALID",
                        "Adding a CHECK constraint reads every existing row to test the condition, and holds "
                                + "an ACCESS EXCLUSIVE lock while it does: neither reads nor writes get through. It "
                                + "also fails if one row breaks the condition.",
                        "Add the constraint with NOT VALID, which is quick, then run ALTER TABLE ... VALIDATE "
                                + "CONSTRAINT as a second statement. Validation reads the rows under a lock that lets "
                                + "reads and writes continue."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ConstraintAdded change, RuleContext context) {
        if (!(change.constraint() instanceof Check check) || check.notValid() || !context.mayHoldRows(change.table())) {
            return Optional.empty();
        }
        String message =
                "Adding check constraint " + check.name().orElse("(unnamed)") + " reads every row of " + change.table()
                        + " while reads and writes wait.";
        return found(change, message, Operation.ADD_CHECK);
    }
}
