package io.github.hanumanthraghavendra.schemaguard.engine.lint;

import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ReportFormatter;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A lint report as plain text: the score, then each statement that has findings with the
 * line it starts on, then what the linter could not judge.
 */
public final class LintFormatter {

    private static final String INDENT = "      ";

    private LintFormatter() {}

    public static String format(LintReport report) {
        StringBuilder out = new StringBuilder();
        out.append("Risk: ").append(report.score().value()).append("/100 ").append(report.score().level()).append('\n');
        out.append("Findings: ").append(report.findings().size()).append(counts(report)).append('\n');

        for (StatementLint statement : report.statements()) {
            if (statement.findings().isEmpty()) {
                continue;
            }
            out.append("\nline ").append(statement.span().line()).append(": ").append(firstLine(statement.sql())).append('\n');
            for (LintFinding finding : statement.findings()) {
                RuleInfo rule = finding.rule();
                out.append("  ").append(String.format("%-8s", finding.severity())).append(' ').append(rule.id());
                out.append("  ").append(rule.title());
                out.append("  [").append(rule.category().label()).append(", score ").append(finding.score()).append("]\n");
                out.append(INDENT).append(finding.message()).append('\n');
                finding.operation()
                        .flatMap(operation -> ReportFormatter.effect(operation, statement.table(), finding.referenced()))
                        .ifPresent(effect -> out.append(INDENT).append("Lock: ").append(effect).append('\n'));
                out.append(INDENT).append("Safer: ").append(rule.fix()).append('\n');
            }
        }

        long quiet = report.statements().stream().filter(s -> s.status() == StatementLint.Status.ANALYSED && s.findings().isEmpty()).count();
        out.append('\n').append(quiet).append(quiet == 1 ? " statement" : " statements").append(" without findings\n");
        for (StatementLint statement : report.statements()) {
            if (statement.status() == StatementLint.Status.NOT_ANALYSED || statement.status() == StatementLint.Status.WOULD_FAIL) {
                String why = statement.status() == StatementLint.Status.WOULD_FAIL ? "would fail" : "not analysed";
                out.append("line ").append(statement.span().line()).append(' ').append(why).append(": ");
                out.append(firstLine(statement.sql())).append('\n');
            }
        }
        for (Diagnostic diagnostic : report.diagnostics()) {
            out.append("  ").append(lower(diagnostic.severity())).append(" at line ").append(diagnostic.span().line());
            out.append(": ").append(diagnostic.message()).append('\n');
        }
        return out.toString();
    }

    private static String counts(LintReport report) {
        List<String> parts = new ArrayList<>();
        for (RiskLevel level : List.of(RiskLevel.CRITICAL, RiskLevel.HIGH, RiskLevel.MEDIUM, RiskLevel.LOW)) {
            long count = report.findings().stream().filter(finding -> finding.severity() == level).count();
            if (count > 0) {
                parts.add(count + " " + lower(level));
            }
        }
        return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
    }

    /** The first line of a statement, cut to 100 characters. */
    static String firstLine(String sql) {
        String line = sql.strip().lines().findFirst().orElse("").strip();
        return line.length() > 100 ? line.substring(0, 97) + "..." : line;
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
