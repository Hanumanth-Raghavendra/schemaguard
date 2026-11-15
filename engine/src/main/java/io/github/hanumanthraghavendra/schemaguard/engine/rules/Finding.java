package io.github.hanumanthraghavendra.schemaguard.engine.rules;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import java.util.Objects;
import java.util.Optional;

/**
 * One risk found in one change.
 *
 * @param rule      the rule that found it
 * @param change    the change it is about
 * @param table     the table that is affected, if the change belongs to one
 * @param message   what happens, naming the objects involved
 * @param operation the statement that applies the change the direct way, if one statement
 *                  stands for the risk; it carries the lock taken and the work done
 * @param certainty from 0 to 1: how sure it is that the finding applies. Below 1 for findings
 *                  that rest on a guess, such as a possible rename
 */
public record Finding(
        RuleInfo rule,
        Change change,
        Optional<QualifiedName> table,
        String message,
        Optional<Operation> operation,
        double certainty)
        implements Scored {

    public Finding {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(operation, "operation");
        if (!(certainty > 0 && certainty <= 1)) {
            throw new IllegalArgumentException("certainty must be above 0 and at most 1: " + certainty);
        }
    }

    public RiskLevel severity() {
        return rule.severity();
    }

    /** From 1 to 100: the weight of the rule's severity, scaled by the certainty. */
    @Override
    public int score() {
        return Math.max(1, (int) Math.round(rule.severity().weight() * certainty));
    }

    public Finding withOperation(Operation value) {
        return new Finding(rule, change, table, message, Optional.of(value), certainty);
    }

    public Finding withCertainty(double value) {
        return new Finding(rule, change, table, message, operation, value);
    }
}
