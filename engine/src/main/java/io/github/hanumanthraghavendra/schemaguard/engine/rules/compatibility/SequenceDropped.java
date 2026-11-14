package io.github.hanumanthraghavendra.schemaguard.engine.rules.compatibility;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Identity;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Sequence;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ChangeRule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import java.util.Optional;

/**
 * A dropped sequence that is not owned by a dropped table or column. A sequence that goes away
 * with its owner is not reported.
 */
public final class SequenceDropped extends ChangeRule<Change.SequenceDropped> {

    public SequenceDropped() {
        super(
                Change.SequenceDropped.class,
                new RuleInfo(
                        "SG-R504",
                        "sequence-dropped",
                        Category.COMPATIBILITY,
                        RiskLevel.MEDIUM,
                        "Sequence is dropped",
                        "Everything that calls nextval on the sequence fails once it is gone: application "
                                + "code, functions, and column defaults. The current position of the sequence is lost "
                                + "as well, so a sequence created again under the same name starts from 1 and hands out "
                                + "numbers that were used before.",
                        "Search the application and the database for the sequence name before dropping it. If "
                                + "the numbers must continue, note the last value with SELECT last_value FROM the "
                                + "sequence first."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.SequenceDropped change, RuleContext context) {
        Sequence sequence = change.sequence();
        boolean goesWithOwner =
                sequence.ownedBy()
                        .filter(
                                owner ->
                                        context.tableAfter(owner.table())
                                                .flatMap(table -> table.column(owner.column()))
                                                .filter(column -> column.identity() == Identity.NONE)
                                                .isEmpty())
                        .isPresent();
        if (goesWithOwner) {
            return Optional.empty();
        }
        String message =
                "Sequence " + sequence.name() + " is dropped. Every nextval on it starts to fail, and its position is lost.";
        return found(change, message);
    }
}
