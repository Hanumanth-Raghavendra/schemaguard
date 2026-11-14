package io.github.hanumanthraghavendra.schemaguard.engine.rules.compatibility;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ChangeRule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import io.github.hanumanthraghavendra.schemaguard.engine.sql.SqlWriter;
import java.util.Optional;

/**
 * A value added to an enum that already exists.
 */
public final class EnumValueAdded extends ChangeRule<Change.EnumValueAdded> {

    public EnumValueAdded() {
        super(
                Change.EnumValueAdded.class,
                new RuleInfo(
                        "SG-R508",
                        "enum-value-added",
                        Category.COMPATIBILITY,
                        RiskLevel.LOW,
                        "New enum value needs care in two places",
                        "ALTER TYPE ... ADD VALUE takes no table lock, but the new value cannot be used in "
                                + "the transaction that adds it: a migration that adds the value and then writes it in "
                                + "the same transaction fails. Application code that reads the column and does not know "
                                + "the value yet may also fail on the first row that holds it.",
                        "Add the value in a migration of its own. Release the code that can read the new "
                                + "value before the code that writes it."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.EnumValueAdded change, RuleContext context) {
        String message =
                "Value " + SqlWriter.literal(change.value()) + " is added to enum " + change.type()
                        + ". It cannot be used until the transaction that adds it has committed.";
        return found(change, message, Operation.ADD_ENUM_VALUE);
    }
}
