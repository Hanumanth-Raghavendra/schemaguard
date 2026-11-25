package io.github.hanumanthraghavendra.schemaguard.cli;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.ChangeFormatter;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.SchemaDiff;
import io.github.hanumanthraghavendra.schemaguard.engine.lint.LintFinding;
import io.github.hanumanthraghavendra.schemaguard.engine.lint.LintFormatter;
import io.github.hanumanthraghavendra.schemaguard.engine.lint.LintReport;
import io.github.hanumanthraghavendra.schemaguard.engine.lint.StatementLint;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.planner.MigrationPlan;
import io.github.hanumanthraghavendra.schemaguard.engine.planner.Rename;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.DirectRoute;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ReportFormatter;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskReport;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskScore;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Optional;

/**
 * What the CLI prints as JSON: the same documents the API returns for the same analysis, field
 * for field, so a script can switch between the two (D-107). Each record is named after its
 * schema in the API contract ({@code web/openapi.json}), and {@code ContractTest} checks every
 * field, its type and whether it may be null against that contract.
 */
public final class Documents {

    private Documents() {}

    /** Marks a field that may be null, as the contract allows. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    public @interface Nullable {}

    public record NameDto(String schema, String name) {}

    /** A rename the diff offers as a candidate; {@code kind} tells the three apart. */
    public sealed interface RenameDto permits TableRename, ColumnRename, EnumValueRename {}

    public record TableRename(String kind, NameDto from, NameDto to) implements RenameDto {}

    public record ColumnRename(String kind, NameDto table, String from, String to) implements RenameDto {}

    public record EnumValueRename(String kind, NameDto type, String from, String to) implements RenameDto {}

    public record Diagnostic(String input, String severity, String code, String message, int line, int column) {}

    public record Change(
            String kind,
            String summary,
            boolean cosmetic,
            @Nullable NameDto table,
            @Nullable RenameDto rename,
            @Nullable Double confidence) {}

    public record DiffResponse(List<Change> changes, int significant, List<Diagnostic> warnings, String text) {}

    public record Operation(
            String name,
            String statement,
            @Nullable String lock,
            @Nullable String lockEffect,
            @Nullable String referencedLock,
            String work) {}

    public record Finding(
            String ruleId,
            String rule,
            String category,
            String severity,
            String title,
            String message,
            String why,
            String fix,
            int score,
            double certainty,
            @Nullable NameDto table,
            @Nullable Operation operation,
            @Nullable String effect,
            @Nullable String change) {}

    public record Score(int value, String level, List<String> reasons) {}

    public record ReportResponse(Score score, List<Finding> findings, List<Diagnostic> warnings, String text) {}

    public record Step(
            String sql,
            @Nullable Operation operation,
            @Nullable NameDto table,
            boolean transactional,
            String note,
            List<String> cautions) {}

    public record Unplanned(String change, String reason) {}

    public record PlanResponse(
            String route,
            boolean complete,
            boolean needsDecisions,
            List<Step> steps,
            List<Unplanned> unplanned,
            List<Diagnostic> warnings,
            String sql) {}

    public record LintedStatement(
            int line,
            int column,
            String sql,
            String status,
            @Nullable NameDto table,
            List<Operation> operations,
            List<Finding> findings) {}

    public record LintResponse(
            Score score, List<LintedStatement> statements, List<Diagnostic> diagnostics, List<Diagnostic> warnings, String text) {}

    // CLI only: a folder of migrations, each checked against the schema the ones before it build.

    /** One migration file of a folder and its check. */
    public record CheckedFile(String file, LintResponse lint) {}

    /** The files checked, in the order they run, and the worst score among them. */
    public record MigrationsResponse(Score score, List<CheckedFile> files) {}

    // ---------------------------------------------------------------- from the engine

    static DiffResponse diff(SchemaDiff diff, List<Diagnostic> warnings) {
        return new DiffResponse(
                diff.changes().stream().map(Documents::change).toList(),
                diff.significant().size(),
                warnings,
                ChangeFormatter.format(diff));
    }

    static ReportResponse report(RiskReport report, List<Diagnostic> warnings) {
        return new ReportResponse(
                score(report.score()),
                report.findings().stream().map(Documents::finding).toList(),
                warnings,
                ReportFormatter.format(report));
    }

    static PlanResponse plan(MigrationPlan plan, List<Diagnostic> warnings) {
        return new PlanResponse(
                plan.route().name(),
                plan.isComplete(),
                plan.needsDecisions(),
                plan.steps().stream()
                        .map(step -> new Step(
                                step.sql(),
                                step.operation().map(Documents::operation).orElse(null),
                                step.table().map(Documents::name).orElse(null),
                                step.transactional(),
                                step.note(),
                                step.cautions()))
                        .toList(),
                plan.unplanned().stream()
                        .map(item -> new Unplanned(ChangeFormatter.format(item.change()), item.reason()))
                        .toList(),
                warnings,
                plan.toSql());
    }

    static LintResponse lint(LintReport report, List<Diagnostic> warnings) {
        return new LintResponse(
                score(report.score()),
                report.statements().stream().map(Documents::statement).toList(),
                report.diagnostics().stream().map(d -> diagnostic("migration", d)).toList(),
                warnings,
                LintFormatter.format(report));
    }

    static Diagnostic diagnostic(String input, io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic diagnostic) {
        return new Diagnostic(
                input,
                diagnostic.severity().name(),
                diagnostic.code(),
                diagnostic.message(),
                diagnostic.span().line(),
                diagnostic.span().column());
    }

    static Score score(RiskScore score) {
        return new Score(score.value(), score.level().name(), score.reasons());
    }

    private static NameDto name(QualifiedName name) {
        return new NameDto(name.schema(), name.name());
    }

    private static Change change(io.github.hanumanthraghavendra.schemaguard.engine.diff.Change change) {
        Double confidence =
                switch (change) {
                    case io.github.hanumanthraghavendra.schemaguard.engine.diff.Change.TableRenameCandidate c -> c.confidence();
                    case io.github.hanumanthraghavendra.schemaguard.engine.diff.Change.ColumnRenameCandidate c -> c.confidence();
                    case io.github.hanumanthraghavendra.schemaguard.engine.diff.Change.EnumValueRenameCandidate c -> c.confidence();
                    default -> null;
                };
        return new Change(
                change.getClass().getSimpleName(),
                ChangeFormatter.format(change),
                change.cosmetic(),
                RuleContext.tableOf(change).map(Documents::name).orElse(null),
                Rename.of(change).map(Documents::rename).orElse(null),
                confidence);
    }

    private static RenameDto rename(Rename rename) {
        return switch (rename) {
            case Rename.OfTable r -> new TableRename("table", name(r.from()), name(r.to()));
            case Rename.OfColumn r -> new ColumnRename("column", name(r.table()), r.from(), r.to());
            case Rename.OfEnumValue r -> new EnumValueRename("enumValue", name(r.type()), r.from(), r.to());
        };
    }

    private static Operation operation(io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation operation) {
        return new Operation(
                operation.name(),
                operation.label(),
                operation.lock().map(lock -> lock.sqlName()).orElse(null),
                operation.lock().map(lock -> lock.effect()).orElse(null),
                operation.referencedLock().map(lock -> lock.sqlName()).orElse(null),
                operation.work().name());
    }

    private static Finding finding(io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding finding) {
        return finding(
                finding.rule(),
                finding.message(),
                finding.score(),
                finding.certainty(),
                finding.table(),
                finding.operation(),
                DirectRoute.referencedTables(finding.change()),
                ChangeFormatter.format(finding.change()));
    }

    private static LintedStatement statement(StatementLint statement) {
        return new LintedStatement(
                statement.span().line(),
                statement.span().column(),
                statement.sql(),
                statement.status().name(),
                statement.table().map(Documents::name).orElse(null),
                statement.operations().stream().map(Documents::operation).toList(),
                statement.findings().stream()
                        .map(finding -> finding(finding, statement.table()))
                        .toList());
    }

    private static Finding finding(LintFinding finding, Optional<QualifiedName> table) {
        return finding(
                finding.rule(), finding.message(), finding.score(), finding.certainty(), table, finding.operation(), finding.referenced(), null);
    }

    private static Finding finding(
            RuleInfo rule,
            String message,
            int score,
            double certainty,
            Optional<QualifiedName> table,
            Optional<io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation> operation,
            List<QualifiedName> referenced,
            String change) {
        return new Finding(
                rule.id(),
                rule.name(),
                rule.category().label(),
                rule.severity().name(),
                rule.title(),
                message,
                rule.why(),
                rule.fix(),
                score,
                certainty,
                table.map(Documents::name).orElse(null),
                operation.map(Documents::operation).orElse(null),
                operation.flatMap(op -> ReportFormatter.effect(op, table, referenced)).orElse(null),
                change);
    }
}
