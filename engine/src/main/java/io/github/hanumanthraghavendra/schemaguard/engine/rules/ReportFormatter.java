package io.github.hanumanthraghavendra.schemaguard.engine.rules;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.ChangeFormatter;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A risk report as plain text, for the command line, logs and golden-file tests.
 *
 * <p>The score comes first. Then every change that has findings is printed as in the diff,
 * followed by its findings, most severe first. Changes without findings are only counted.
 */
public final class ReportFormatter {

    private static final String INDENT = "      ";

    private ReportFormatter() {}

    public static String format(RiskReport report) {
        StringBuilder out = new StringBuilder();
        RiskScore score = report.score();
        out.append("Risk: ").append(score.value()).append("/100 ").append(score.level()).append('\n');
        out.append("Findings: ").append(report.findings().size()).append(counts(report)).append('\n');

        int quiet = 0;
        for (Change change : report.diff().changes()) {
            List<Finding> findings = report.findingsFor(change);
            if (findings.isEmpty()) {
                quiet++;
                continue;
            }
            out.append('\n').append(ChangeFormatter.format(change)).append('\n');
            findings.forEach(finding -> appendFinding(out, finding));
        }
        if (quiet > 0) {
            out.append('\n').append(quiet).append(quiet == 1 ? " change" : " changes").append(" without findings\n");
        }
        return out.toString();
    }

    private static String counts(RiskReport report) {
        List<String> parts = new ArrayList<>();
        for (RiskLevel level : List.of(RiskLevel.CRITICAL, RiskLevel.HIGH, RiskLevel.MEDIUM, RiskLevel.LOW)) {
            long count = report.count(level);
            if (count > 0) {
                parts.add(count + " " + level.name().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
    }

    private static void appendFinding(StringBuilder out, Finding finding) {
        RuleInfo rule = finding.rule();
        out.append("  ").append(String.format("%-8s", finding.severity())).append(' ').append(rule.id());
        out.append("  ").append(rule.title());
        out.append("  [").append(rule.category().label()).append(", score ").append(finding.score()).append("]\n");
        out.append(INDENT).append(finding.message()).append('\n');
        finding.operation()
                .flatMap(operation -> effect(operation, finding.table(), DirectRoute.referencedTables(finding.change())))
                .ifPresent(effect -> out.append(INDENT).append("Direct route: ").append(effect).append('\n'));
        out.append(INDENT).append("Safer: ").append(rule.fix()).append('\n');
    }

    /**
     * One line saying what the statement locks and for how long, or empty if it locks nothing.
     *
     * @param table      the table the statement works on, if known
     * @param referenced the other tables it locks through a foreign key; the lock on them is
     *                   only mentioned when there are some
     */
    public static Optional<String> effect(Operation operation, Optional<QualifiedName> table, List<QualifiedName> referenced) {
        List<String> locks = new ArrayList<>();
        operation.lock().ifPresent(lock -> locks.add(describe(lock, table.map(QualifiedName::toString).orElse("the table"))));
        if (!referenced.isEmpty()) {
            String names = String.join(", ", referenced.stream().map(QualifiedName::toString).toList());
            operation.referencedLock().ifPresent(lock -> locks.add(describe(lock, names)));
        }
        if (locks.isEmpty()) {
            return Optional.empty();
        }
        String held =
                switch (operation.work()) {
                    case NONE -> "held briefly";
                    case SCAN -> "held while every row is read";
                    case REWRITE -> "held while the whole table is written again";
                };
        return Optional.of(operation.label() + " takes " + String.join(" and ", locks) + ", " + held);
    }

    private static String describe(LockLevel lock, String table) {
        return lock.sqlName() + " on " + table + " (" + lock.effect() + ")";
    }
}
