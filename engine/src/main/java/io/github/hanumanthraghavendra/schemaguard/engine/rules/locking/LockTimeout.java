package io.github.hanumanthraghavendra.schemaguard.engine.rules.locking;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.LockLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.DirectRoute;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Rule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reported once for a migration, however many statements need the lock. It names the existing
 * tables that are locked ACCESS EXCLUSIVE.
 */
public final class LockTimeout extends Rule {

    public LockTimeout() {
        super(
                new RuleInfo(
                        "SG-R111",
                        "lock-timeout",
                        Category.LOCKING,
                        RiskLevel.LOW,
                        "Statements that need a full lock should run with a lock timeout",
                        "An ACCESS EXCLUSIVE lock has to wait for every query already running on the table, "
                                + "even a slow report. While it waits, every new query on that table waits behind it. A "
                                + "statement that takes a millisecond can so stop an application for minutes.",
                        "Run SET lock_timeout = '5s' before the migration and try again if it times out. A "
                                + "failed attempt costs nothing; a waiting one blocks everybody."));
    }

    @Override
    public List<Finding> check(RuleContext context) {
        Set<QualifiedName> tables = new LinkedHashSet<>();
        Change first = null;
        for (Change change : context.diff().changes()) {
            Optional<QualifiedName> table = RuleContext.tableOf(change).filter(context::mayHoldRows);
            boolean fullLock =
                    DirectRoute.of(change, context).flatMap(Operation::lock).filter(LockLevel.ACCESS_EXCLUSIVE::equals).isPresent();
            if (!change.cosmetic() && table.isPresent() && fullLock) {
                tables.add(table.get());
                first = first == null ? change : first;
            }
        }
        if (first == null) {
            return List.of();
        }
        String names = tables.stream().map(QualifiedName::toString).collect(Collectors.joining(", "));
        String message =
                (tables.size() == 1 ? "This migration locks " : "This migration locks " + tables.size() + " tables ")
                        + names + " against reads and writes. Each statement first waits for the queries already running there,"
                        + " and blocks all new ones while it waits.";
        return List.of(finding(first, message));
    }
}
