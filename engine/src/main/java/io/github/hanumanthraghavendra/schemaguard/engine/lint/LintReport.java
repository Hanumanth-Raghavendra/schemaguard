package io.github.hanumanthraghavendra.schemaguard.engine.lint;

import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskScore;
import java.util.List;

/**
 * The result of linting a migration script.
 *
 * @param statements  every statement of the script, in order, with its findings
 * @param diagnostics what the parser could not read and what PostgreSQL would refuse
 * @param result      the schema after the script, as far as its statements could be applied
 * @param score       the overall risk of the script as written
 */
public record LintReport(List<StatementLint> statements, List<Diagnostic> diagnostics, Catalog result, RiskScore score) {

    public LintReport {
        statements = List.copyOf(statements);
        diagnostics = List.copyOf(diagnostics);
    }

    public List<LintFinding> findings() {
        return statements.stream().flatMap(statement -> statement.findings().stream()).toList();
    }

    public RiskLevel level() {
        return score.level();
    }

    /** True if no rule found anything. Statements that were not analysed do not count as clean. */
    public boolean isClean() {
        return findings().isEmpty();
    }

    public long count(StatementLint.Status status) {
        return statements.stream().filter(statement -> statement.status() == status).count();
    }
}
