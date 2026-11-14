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
 * An enum whose values are the same but in a different order.
 */
public final class EnumValuesReordered extends ChangeRule<Change.EnumValuesReordered> {

    public EnumValuesReordered() {
        super(
                Change.EnumValuesReordered.class,
                new RuleInfo(
                        "SG-R209",
                        "enum-values-reordered",
                        Category.REWRITE,
                        RiskLevel.HIGH,
                        "Enum values cannot be reordered in place",
                        "The order of enum values is fixed when each value is created. To change it, the type "
                                + "has to be created again and every column that uses it converted, which rewrites each "
                                + "of those tables under an ACCESS EXCLUSIVE lock. The new order also changes how the "
                                + "values sort and compare.",
                        "Leave the order alone unless sorting depends on it; ORDER BY with a CASE expression "
                                + "or a lookup table gives any order without touching the type. New values can be "
                                + "placed with ADD VALUE ... BEFORE or AFTER."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.EnumValuesReordered change, RuleContext context) {
        String message =
                "The values of enum " + change.type() + " are in a different order. The type has to be created again and"
                        + " every column that uses it rewritten.";
        return found(change, message);
    }
}
