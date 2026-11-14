package io.github.hanumanthraghavendra.schemaguard.engine.rules;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.SchemaDiff;
import java.util.List;

/**
 * The result of judging a diff: every risk found, and the overall score.
 *
 * <p>The report describes the <em>direct</em> way of getting from the old schema to the new
 * one, one plain statement per change. That is the migration most people write first, and
 * the one whose risks have to be known before a safer one is chosen.
 *
 * @param diff     the diff that was judged
 * @param findings the risks, in the order of the changes they belong to
 * @param score    the overall risk
 */
public record RiskReport(SchemaDiff diff, List<Finding> findings, RiskScore score) {

    public RiskReport {
        findings = List.copyOf(findings);
    }

    public RiskLevel level() {
        return score.level();
    }

    /** True if no rule found anything. */
    public boolean isClean() {
        return findings.isEmpty();
    }

    /** The findings about one change. */
    public List<Finding> findingsFor(Change change) {
        return findings.stream().filter(finding -> finding.change().equals(change)).toList();
    }

    public long count(RiskLevel severity) {
        return findings.stream().filter(finding -> finding.severity() == severity).count();
    }
}
