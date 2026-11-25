package io.github.hanumanthraghavendra.schemaguard.cli;

import io.github.hanumanthraghavendra.schemaguard.cli.Documents.CheckedFile;
import io.github.hanumanthraghavendra.schemaguard.cli.Documents.Diagnostic;
import io.github.hanumanthraghavendra.schemaguard.cli.Documents.DiffResponse;
import io.github.hanumanthraghavendra.schemaguard.cli.Documents.Finding;
import io.github.hanumanthraghavendra.schemaguard.cli.Documents.LintResponse;
import io.github.hanumanthraghavendra.schemaguard.cli.Documents.LintedStatement;
import io.github.hanumanthraghavendra.schemaguard.cli.Documents.MigrationsResponse;
import io.github.hanumanthraghavendra.schemaguard.cli.Documents.PlanResponse;
import io.github.hanumanthraghavendra.schemaguard.cli.Documents.ReportResponse;
import io.github.hanumanthraghavendra.schemaguard.cli.Documents.Score;
import io.github.hanumanthraghavendra.schemaguard.cli.Documents.Unplanned;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reports as Markdown, worded and laid out as the web app's downloads ({@code web/src/export.ts}),
 * so a pull-request comment reads like a report saved from the site.
 */
final class Markdown {

    private static final Pattern REASON = Pattern.compile("^(\\d+) (SG-[A-Z]+\\d+) (.+)$");

    private Markdown() {}

    /** A risk report, with the changes it judged. */
    static String report(ReportResponse report, DiffResponse diff) {
        List<String> lines = new ArrayList<>(List.of("# SchemaGuard risk report", ""));
        lines.addAll(scoreLines(report.score()));
        lines.addAll(List.of("## Risks", ""));
        if (report.findings().isEmpty()) {
            lines.addAll(List.of("No risks found.", ""));
        }
        ordered(report.findings()).forEach(finding -> lines.addAll(findingLines(finding, "###")));
        lines.addAll(changeLines(diff, "##"));
        return String.join("\n", lines);
    }

    /** Every change between two schemas. */
    static String diff(DiffResponse diff) {
        List<String> lines = new ArrayList<>(List.of("# SchemaGuard schema changes", ""));
        if (diff.changes().isEmpty()) {
            lines.addAll(List.of("The schemas are the same.", ""));
            return String.join("\n", lines);
        }
        lines.addAll(changeLines(diff, "##"));
        return String.join("\n", lines);
    }

    /** A migration plan as a script, with what it could not plan. */
    static String plan(PlanResponse plan) {
        List<String> lines = new ArrayList<>(List.of("# SchemaGuard migration plan", ""));
        lines.add("## Plan (" + plan.route().toLowerCase(Locale.ROOT) + " route)");
        lines.add("");
        lines.add(plan.steps().isEmpty() ? "Nothing to run." : fence(plan.sql()));
        lines.add("");
        for (Unplanned item : plan.unplanned()) {
            lines.add("- Not in the plan: `" + item.change() + "`: " + item.reason());
        }
        if (!plan.unplanned().isEmpty()) {
            lines.add("");
        }
        return String.join("\n", lines);
    }

    /** One checked migration, statement by statement. */
    static String lint(LintResponse lint) {
        List<String> lines = new ArrayList<>(List.of("# SchemaGuard migration check", ""));
        lines.addAll(lintLines(lint, "##"));
        return String.join("\n", lines);
    }

    /** A folder of migrations: the worst score, then each file as {@link #lint} shows one. */
    static String migrations(MigrationsResponse migrations) {
        List<String> lines = new ArrayList<>(List.of("# SchemaGuard migration check", ""));
        if (migrations.files().size() != 1) {
            lines.add("Checked " + migrations.files().size() + " migrations; the riskiest scores "
                    + migrations.score().value() + "/100 (" + sentenceCase(migrations.score().level()) + ").");
            lines.add("");
        }
        for (CheckedFile file : migrations.files()) {
            lines.addAll(List.of("## " + file.file(), ""));
            lines.addAll(lintLines(file.lint(), "###"));
        }
        return String.join("\n", lines);
    }

    // ---------------------------------------------------------------- parts

    private static List<String> lintLines(LintResponse lint, String heading) {
        List<String> lines = new ArrayList<>(scoreLines(lint.score()));
        List<Diagnostic> problems =
                lint.diagnostics().stream().filter(d -> d.input().equals("migration")).toList();
        List<LintedStatement> statements = lint.statements();
        for (int index = 0; index < statements.size(); index++) {
            LintedStatement statement = statements.get(index);
            lines.addAll(List.of(heading + " Line " + statement.line(), "", fence(statement.sql()), ""));
            if (!statement.status().equals("ANALYSED")) {
                lines.addAll(List.of("Status: " + sentenceCase(statement.status()), ""));
            }
            for (Diagnostic problem : problems) {
                if (owner(statements, problem) == index) {
                    lines.addAll(List.of("Error: " + problem.message(), ""));
                }
            }
            ordered(statement.findings()).forEach(finding -> lines.addAll(findingLines(finding, heading + "#")));
        }
        return lines;
    }

    private static List<String> changeLines(DiffResponse diff, String heading) {
        List<String> lines = new ArrayList<>(List.of(heading + " Changes", ""));
        diff.changes().forEach(change -> lines.add("- `" + change.summary() + "`"));
        lines.add("");
        return lines;
    }

    private static List<String> scoreLines(Score score) {
        List<String> lines = new ArrayList<>(List.of("**Risk: " + score.value() + "/100 (" + sentenceCase(score.level()) + ")**", ""));
        if (!score.reasons().isEmpty()) {
            lines.addAll(List.of("Main reasons:", ""));
            for (String reason : score.reasons()) {
                Matcher match = REASON.matcher(reason);
                lines.add(match.matches()
                        ? "- " + match.group(3) + " (" + match.group(2) + ", " + match.group(1) + " points)"
                        : "- " + reason);
            }
            lines.add("");
        }
        return lines;
    }

    private static List<String> findingLines(Finding finding, String heading) {
        List<String> lines = new ArrayList<>(List.of(
                heading + " " + sentenceCase(finding.severity()) + ": " + finding.title() + " (" + finding.ruleId() + ")",
                "",
                finding.message(),
                ""));
        boolean lock = finding.operation() != null && finding.operation().lock() != null;
        if (finding.change() != null) {
            lines.add("- Change: `" + finding.change() + "`");
        }
        if (lock) {
            String effect = finding.operation().lockEffect() == null ? "" : ", " + finding.operation().lockEffect();
            lines.add("- Lock: " + finding.operation().lock() + effect);
        }
        if (finding.change() != null || lock) {
            lines.add("");
        }
        lines.addAll(List.of("Why: " + finding.why(), "", "Instead: " + finding.fix(), ""));
        return lines;
    }

    /** Worst first; equal scores keep their order. */
    private static List<Finding> ordered(List<Finding> findings) {
        return findings.stream().sorted(Comparator.comparingInt(Finding::score).reversed()).toList();
    }

    private static String fence(String sql) {
        return "```sql\n" + sql.stripTrailing() + "\n```";
    }

    /** "NOT_ANALYSED" reads "Not analysed". */
    static String sentenceCase(String value) {
        String lower = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** The statement a problem falls in: the last one that starts at or before it, or -1. */
    private static int owner(List<LintedStatement> statements, Diagnostic problem) {
        int found = -1;
        for (int i = 0; i < statements.size(); i++) {
            LintedStatement statement = statements.get(i);
            if (statement.line() < problem.line()
                    || (statement.line() == problem.line() && statement.column() <= problem.column())) {
                found = i;
            }
        }
        return found;
    }
}
