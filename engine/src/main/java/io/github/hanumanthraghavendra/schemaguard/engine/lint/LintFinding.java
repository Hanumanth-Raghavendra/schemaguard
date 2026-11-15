package io.github.hanumanthraghavendra.schemaguard.engine.lint;

import io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Scored;
import java.util.Objects;
import java.util.Optional;

/**
 * One risk found in one statement of a migration script.
 *
 * @param rule      the rule that found it
 * @param message   what happens, naming the objects involved
 * @param operation the kind of statement that carries the risk, if the risk is a lock
 * @param certainty from 0 to 1: how sure it is that the finding applies
 */
public record LintFinding(RuleInfo rule, String message, Optional<Operation> operation, double certainty) implements Scored {

    public LintFinding {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(operation, "operation");
        if (!(certainty > 0 && certainty <= 1)) {
            throw new IllegalArgumentException("certainty must be above 0 and at most 1: " + certainty);
        }
    }

    public RiskLevel severity() {
        return rule.severity();
    }

    @Override
    public int score() {
        return Math.max(1, (int) Math.round(rule.severity().weight() * certainty));
    }
}
