package io.github.hanumanthraghavendra.schemaguard.engine.rules;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.SchemaDiff;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the rules over a diff and builds the {@link RiskReport}.
 *
 * <pre>{@code
 * SchemaDiff diff = SchemaDiff.compare(before, after);
 * RiskReport report = RiskAnalyzer.analyze(diff);
 * }</pre>
 */
public final class RiskAnalyzer {

    private RiskAnalyzer() {}

    /** Judges the diff with every standard rule. */
    public static RiskReport analyze(SchemaDiff diff) {
        return analyze(diff, RuleRegistry.standard());
    }

    public static RiskReport analyze(SchemaDiff diff, RuleRegistry rules) {
        RuleContext context = new RuleContext(diff);
        List<Finding> findings = new ArrayList<>();
        for (Rule rule : rules.rules()) {
            findings.addAll(rule.check(context));
        }

        // The order of the report must not depend on the order the rules ran in.
        Map<Change, Integer> position = new IdentityHashMap<>();
        for (Change change : diff.changes()) {
            position.put(change, position.size());
        }
        findings.sort(
                Comparator.comparingInt((Finding f) -> position.getOrDefault(f.change(), Integer.MAX_VALUE))
                        .thenComparing(Finding::severity, Comparator.reverseOrder())
                        .thenComparing(f -> f.rule().id()));
        return new RiskReport(diff, findings, RiskScore.of(findings));
    }
}
