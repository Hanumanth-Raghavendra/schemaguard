package io.github.hanumanthraghavendra.schemaguard.engine.rules.dataloss;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Column;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Table;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Rule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * A dropped table or column and an added one that look like the same thing under a new name.
 * The finding counts in proportion to how sure the match is.
 */
public final class PossibleRename extends Rule {

    public PossibleRename() {
        super(
                new RuleInfo(
                        "SG-R305",
                        "possible-rename",
                        Category.DATA_LOSS,
                        RiskLevel.HIGH,
                        "Possible rename would be applied as drop and add",
                        "Two schemas alone cannot tell a rename from a drop followed by an add. The direct "
                                + "route does the second: it drops the old one and creates an empty new one, and the "
                                + "data is gone. If the intent was a rename, that is data loss nobody asked for. A real "
                                + "rename keeps the data, but it still breaks every query that uses the old name.",
                        "If it is a rename, write ALTER TABLE ... RENAME (TO or COLUMN) yourself. To rename "
                                + "without breaking the running application, add the new name next to the old one, "
                                + "write to both, move the readers over, and remove the old name last. If it is not a "
                                + "rename, nothing needs to change."));
    }

    @Override
    public List<Finding> check(RuleContext context) {
        List<Finding> findings = new ArrayList<>();
        context.changes(Change.TableRenameCandidate.class)
                .forEach(
                        rename ->
                                findings.add(
                                        finding(
                                                        rename,
                                                        "Table " + rename.from() + " may have been renamed to " + rename.to()
                                                                + ". Applied as drop and create, all its rows are lost.")
                                                .withCertainty(rename.confidence())));
        context.changes(Change.ColumnRenameCandidate.class)
                .forEach(
                        rename ->
                                findings.add(
                                        finding(
                                                        rename,
                                                        "Column " + rename.table() + "." + rename.from()
                                                                + " may have been renamed to " + rename.to()
                                                                + ". Applied as drop and add, its values are lost.")
                                                .withCertainty(rename.confidence())));
        return findings;
    }
}
