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
 * A removed enum value and an added one that take the same place in the list. The finding
 * counts in proportion to how sure the match is.
 */
public final class PossibleEnumValueRename extends ChangeRule<Change.EnumValueRenameCandidate> {

    public PossibleEnumValueRename() {
        super(
                Change.EnumValueRenameCandidate.class,
                new RuleInfo(
                        "SG-R306",
                        "possible-enum-value-rename",
                        Category.DATA_LOSS,
                        RiskLevel.MEDIUM,
                        "Possible enum value rename would be applied as remove and add",
                        "A removed enum value and an added one may be the same value under a new name. "
                                + "Removing a value means creating the type again and changing every row that holds it; "
                                + "ALTER TYPE ... RENAME VALUE does the same job by changing one catalog row.",
                        "If it is a rename, use ALTER TYPE ... RENAME VALUE. It takes no table lock and keeps "
                                + "every row, but the application must accept the new name at the same moment."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.EnumValueRenameCandidate change, RuleContext context) {
        String message =
                "Value " + SqlWriter.literal(change.from()) + " of enum " + change.type() + " may have been renamed to "
                        + SqlWriter.literal(change.to()) + ". RENAME VALUE keeps the rows that hold it.";
        return found(change, message).map(finding -> finding.withCertainty(change.confidence()));
    }
}
