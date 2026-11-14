package io.github.hanumanthraghavendra.schemaguard.engine.rules.dataloss;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ChangeRule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import java.util.Optional;

/**
 * A table that exists in the old schema and not in the new one.
 */
public final class TableDropped extends ChangeRule<Change.TableDropped> {

    public TableDropped() {
        super(
                Change.TableDropped.class,
                new RuleInfo(
                        "SG-R301",
                        "table-dropped",
                        Category.DATA_LOSS,
                        RiskLevel.CRITICAL,
                        "Table is dropped with all its rows",
                        "DROP TABLE removes every row at once and cannot be undone after the transaction "
                                + "commits. Only a backup brings the data back. Application code that still uses the "
                                + "table fails from that moment.",
                        "First release application code that no longer uses the table. Then rename the table "
                                + "instead of dropping it, wait a release or two to be sure nothing misses it, take a "
                                + "backup, and drop it last."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.TableDropped change, RuleContext context) {
        String message = "Dropping " + change.table().name() + " deletes all its rows for good.";
        return found(change, message, Operation.DROP_TABLE);
    }
}
