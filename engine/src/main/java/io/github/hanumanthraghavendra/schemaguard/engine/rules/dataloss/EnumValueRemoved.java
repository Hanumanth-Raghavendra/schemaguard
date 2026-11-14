package io.github.hanumanthraghavendra.schemaguard.engine.rules.dataloss;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ChangeRule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import io.github.hanumanthraghavendra.schemaguard.engine.sql.SqlWriter;
import java.util.Optional;

/**
 * A value that the old enum has and the new one does not.
 */
public final class EnumValueRemoved extends ChangeRule<Change.EnumValueRemoved> {

    public EnumValueRemoved() {
        super(
                Change.EnumValueRemoved.class,
                new RuleInfo(
                        "SG-R303",
                        "enum-value-removed",
                        Category.DATA_LOSS,
                        RiskLevel.HIGH,
                        "Enum value is removed",
                        "PostgreSQL has no statement to remove a value from an enum. The type has to be "
                                + "created again without the value and every column converted to it, which rewrites "
                                + "those tables. The conversion fails while any row still holds the value, so those "
                                + "rows have to be changed or deleted first.",
                        "Stop writing the value and update the rows that hold it. Then create the new type, "
                                + "convert each column with ALTER COLUMN ... TYPE ... USING, and drop the old type. "
                                + "Often it is simpler to leave the value in the type and stop using it."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.EnumValueRemoved change, RuleContext context) {
        String message =
                "Value " + SqlWriter.literal(change.value()) + " is removed from enum " + change.type()
                        + ". The type has to be created again, and rows that hold the value must be changed first.";
        return found(change, message);
    }
}
