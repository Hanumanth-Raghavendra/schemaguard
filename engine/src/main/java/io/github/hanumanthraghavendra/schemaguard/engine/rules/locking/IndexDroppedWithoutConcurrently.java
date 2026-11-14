package io.github.hanumanthraghavendra.schemaguard.engine.rules.locking;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Index;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ChangeRule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import java.util.Optional;

/**
 * An index dropped from a table that stays. Indexes that go away with their table are not
 * reported.
 */
public final class IndexDroppedWithoutConcurrently extends ChangeRule<Change.IndexDropped> {

    public IndexDroppedWithoutConcurrently() {
        super(
                Change.IndexDropped.class,
                new RuleInfo(
                        "SG-R102",
                        "index-dropped-without-concurrently",
                        Category.LOCKING,
                        RiskLevel.MEDIUM,
                        "Index is dropped without CONCURRENTLY",
                        "DROP INDEX takes an ACCESS EXCLUSIVE lock on the table. The drop itself is quick, "
                                + "but it has to wait for every running query on the table, and while it waits all new "
                                + "queries queue up behind it. Queries that used the index also become slower from that "
                                + "moment.",
                        "Use DROP INDEX CONCURRENTLY, which waits without blocking anyone. First check that "
                                + "the index is no longer used: pg_stat_user_indexes shows how often it is scanned."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.IndexDropped change, RuleContext context) {
        Index index = change.index();
        if (!context.kept(index.table())) {
            return Optional.empty();
        }
        String message =
                "Dropping index " + index.name().orElse("(unnamed)") + " locks " + index.table()
                        + " against reads and writes while it waits for running queries."
                        + (index.unique() ? " The uniqueness it enforced is gone afterwards." : "");
        return found(change, message, Operation.DROP_INDEX);
    }
}
