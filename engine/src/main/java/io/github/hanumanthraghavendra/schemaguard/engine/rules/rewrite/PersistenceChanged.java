package io.github.hanumanthraghavendra.schemaguard.engine.rules.rewrite;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Persistence;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ChangeRule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import java.util.Locale;
import java.util.Optional;

/**
 * A table that changes between permanent, unlogged and temporary.
 */
public final class PersistenceChanged extends ChangeRule<Change.TablePersistenceChanged> {

    public PersistenceChanged() {
        super(
                Change.TablePersistenceChanged.class,
                new RuleInfo(
                        "SG-R207",
                        "persistence-changed",
                        Category.REWRITE,
                        RiskLevel.HIGH,
                        "Switching between LOGGED and UNLOGGED rewrites the table",
                        "A logged table is written to the write-ahead log and an unlogged one is not, so "
                                + "changing between them means writing all rows again. PostgreSQL writes every row into "
                                + "a new copy of the table and rebuilds every index, all under an ACCESS EXCLUSIVE "
                                + "lock: no reads, no writes, for as long as it takes. It also needs free disk space "
                                + "for the second copy. An unlogged table is also emptied after a crash and is not "
                                + "copied to replicas.",
                        "Create a new table with the wanted persistence, copy the rows in batches while a "
                                + "trigger keeps both in step, then swap the names in one short transaction."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.TablePersistenceChanged change, RuleContext context) {
        if (!context.kept(change.table())) {
            return Optional.empty();
        }
        String message =
                "Changing " + change.table() + " from " + word(change.before()) + " to " + word(change.after())
                        + " rewrites the whole table while reads and writes wait."
                        + (change.after() == Persistence.UNLOGGED
                                ? " An unlogged table loses all rows after a crash and is not replicated."
                                : "");
        return found(change, message, Operation.SET_UNLOGGED);
    }

    private static String word(Persistence persistence) {
        return persistence == Persistence.PERMANENT ? "logged" : persistence.name().toLowerCase(Locale.ROOT);
    }
}
