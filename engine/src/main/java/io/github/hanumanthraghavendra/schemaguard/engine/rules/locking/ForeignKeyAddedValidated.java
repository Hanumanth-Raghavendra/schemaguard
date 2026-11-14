package io.github.hanumanthraghavendra.schemaguard.engine.rules.locking;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ForeignKey;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ChangeRule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import java.util.Optional;

/**
 * A foreign key, not marked NOT VALID, added to a table that already has rows.
 */
public final class ForeignKeyAddedValidated extends ChangeRule<Change.ConstraintAdded> {

    public ForeignKeyAddedValidated() {
        super(
                Change.ConstraintAdded.class,
                new RuleInfo(
                        "SG-R104",
                        "foreign-key-added-validated",
                        Category.LOCKING,
                        RiskLevel.HIGH,
                        "Foreign key is added without NOT VALID",
                        "Adding a foreign key checks every existing row against the referenced table. For the "
                                + "whole check it holds a SHARE ROW EXCLUSIVE lock on both tables, which blocks all "
                                + "writes to them. It also fails if one row points to a row that does not exist.",
                        "Add the constraint with NOT VALID: it then applies to new rows only and is quick. "
                                + "Run ALTER TABLE ... VALIDATE CONSTRAINT as a second statement; it checks the "
                                + "existing rows without blocking reads or writes."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ConstraintAdded change, RuleContext context) {
        if (!(change.constraint() instanceof ForeignKey key) || key.notValid() || !context.mayHoldRows(change.table())) {
            return Optional.empty();
        }
        String message =
                "Adding foreign key " + key.name().orElse("(unnamed)") + " checks every row of " + change.table()
                        + " against " + key.referencedTable() + ". Writes to both tables wait until the check ends.";
        return found(change, message, Operation.ADD_FOREIGN_KEY);
    }
}
