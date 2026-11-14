package io.github.hanumanthraghavendra.schemaguard.engine.rules.compatibility;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ChangeRule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import java.util.Optional;

/**
 * A table whose INHERITS list differs between the two schemas.
 */
public final class InheritanceChanged extends ChangeRule<Change.TableInheritanceChanged> {

    public InheritanceChanged() {
        super(
                Change.TableInheritanceChanged.class,
                new RuleInfo(
                        "SG-R509",
                        "inheritance-changed",
                        Category.COMPATIBILITY,
                        RiskLevel.MEDIUM,
                        "Table changes its parent tables",
                        "A query on a parent table also returns the rows of its children. Attaching or "
                                + "detaching a parent therefore changes the result of every query on that parent, "
                                + "without any change to the query. Attaching also needs an ACCESS EXCLUSIVE lock on "
                                + "the child and requires it to have all the columns and CHECK constraints of the "
                                + "parent already.",
                        "Check every query that reads the parent tables, and use ONLY where the children must "
                                + "not be included."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.TableInheritanceChanged change, RuleContext context) {
        if (!context.kept(change.table())) {
            return Optional.empty();
        }
        String message =
                "The parent tables of " + change.table() + " change. Queries on a parent start or stop returning the rows of "
                        + change.table() + ".";
        return found(change, message);
    }
}
