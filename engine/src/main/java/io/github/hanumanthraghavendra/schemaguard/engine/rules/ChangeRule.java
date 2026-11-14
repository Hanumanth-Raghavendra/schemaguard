package io.github.hanumanthraghavendra.schemaguard.engine.rules;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A rule that judges each change of one kind by itself, which is what most rules do.
 *
 * @param <C> the kind of change the rule looks at
 */
public abstract class ChangeRule<C extends Change> extends Rule {

    private final Class<C> changeType;

    protected ChangeRule(Class<C> changeType, RuleInfo info) {
        super(info);
        this.changeType = changeType;
    }

    @Override
    public final List<Finding> check(RuleContext context) {
        List<Finding> findings = new ArrayList<>();
        context.changes(changeType).forEach(change -> evaluate(change, context).ifPresent(findings::add));
        return findings;
    }

    /** The finding for this change, or empty if the change is safe as far as this rule goes. */
    protected abstract Optional<Finding> evaluate(C change, RuleContext context);
}
