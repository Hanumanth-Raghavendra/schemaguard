package io.github.hanumanthraghavendra.schemaguard.engine.rules;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import java.util.List;
import java.util.Optional;

/**
 * One check for one kind of risk. Each rule is a class of its own, so a rule can be read,
 * tested, documented and switched off without touching any other.
 *
 * <p>Rules do not look at cosmetic changes, and they are stateless: the same rule object is
 * used for every report.
 */
public abstract class Rule {

    private final RuleInfo info;

    protected Rule(RuleInfo info) {
        this.info = info;
    }

    public final RuleInfo info() {
        return info;
    }

    /** Every finding of this rule in the diff the context describes. */
    public abstract List<Finding> check(RuleContext context);

    /** A finding of this rule about {@code change}, on the table the change belongs to. */
    protected final Finding finding(Change change, String message) {
        return new Finding(info, change, RuleContext.tableOf(change), message, Optional.empty(), 1.0);
    }

    /** A finding of this rule about {@code change}, with the statement that stands for the risk. */
    protected final Optional<Finding> found(Change change, String message, Operation operation) {
        return Optional.of(finding(change, message).withOperation(operation));
    }

    protected final Optional<Finding> found(Change change, String message) {
        return Optional.of(finding(change, message));
    }

    @Override
    public final String toString() {
        return info.id() + " " + info.name();
    }
}
