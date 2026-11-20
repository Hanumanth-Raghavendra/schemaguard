package io.github.hanumanthraghavendra.schemaguard.api.service;

import io.github.hanumanthraghavendra.schemaguard.api.dto.NameDto;
import io.github.hanumanthraghavendra.schemaguard.api.dto.RenameDto;
import io.github.hanumanthraghavendra.schemaguard.api.dto.Responses;
import io.github.hanumanthraghavendra.schemaguard.engine.diagram.ErModel;
import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.ChangeFormatter;
import io.github.hanumanthraghavendra.schemaguard.engine.lint.LintFinding;
import io.github.hanumanthraghavendra.schemaguard.engine.lint.StatementLint;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.planner.MigrationPlan;
import io.github.hanumanthraghavendra.schemaguard.engine.planner.Rename;
import io.github.hanumanthraghavendra.schemaguard.engine.planner.Step;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.DirectRoute;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ReportFormatter;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskScore;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import java.util.List;
import java.util.Optional;

/**
 * Turns engine results into the API's own types. The engine's records never leave the
 * service, so the engine can change without changing the API.
 */
final class DtoMapper {

    private DtoMapper() {}

    static Responses.ModelTable table(ErModel.Entity table) {
        return new Responses.ModelTable(
                name(table.name()),
                table.status().name(),
                table.columns().stream()
                        .map(column -> new Responses.ModelColumn(
                                column.name(),
                                column.type(),
                                column.notNull(),
                                column.primaryKey(),
                                column.status().name(),
                                column.previousType().orElse(null)))
                        .toList());
    }

    static Responses.ModelReference reference(ErModel.Relationship relationship) {
        return new Responses.ModelReference(
                relationship.name().orElse(null),
                name(relationship.from()),
                relationship.columns(),
                name(relationship.to()),
                relationship.referencedColumns(),
                relationship.status().name());
    }

    static NameDto name(QualifiedName name) {
        return new NameDto(name.schema(), name.name());
    }

    static QualifiedName name(NameDto name) {
        return new QualifiedName(name.schema(), name.name());
    }

    static Responses.Diagnostic diagnostic(String input, Diagnostic diagnostic) {
        return new Responses.Diagnostic(
                input,
                diagnostic.severity().name(),
                diagnostic.code(),
                diagnostic.message(),
                diagnostic.span().line(),
                diagnostic.span().column());
    }

    static Responses.Change change(Change change) {
        Optional<Rename> rename = Rename.of(change);
        Double confidence =
                switch (change) {
                    case Change.TableRenameCandidate c -> c.confidence();
                    case Change.ColumnRenameCandidate c -> c.confidence();
                    case Change.EnumValueRenameCandidate c -> c.confidence();
                    default -> null;
                };
        return new Responses.Change(
                change.getClass().getSimpleName(),
                ChangeFormatter.format(change),
                change.cosmetic(),
                RuleContext.tableOf(change).map(DtoMapper::name).orElse(null),
                rename.map(DtoMapper::rename).orElse(null),
                confidence);
    }

    static RenameDto rename(Rename rename) {
        return switch (rename) {
            case Rename.OfTable r -> new RenameDto.Table(name(r.from()), name(r.to()));
            case Rename.OfColumn r -> new RenameDto.Column(name(r.table()), r.from(), r.to());
            case Rename.OfEnumValue r -> new RenameDto.EnumValue(name(r.type()), r.from(), r.to());
        };
    }

    static Rename rename(RenameDto rename) {
        return switch (rename) {
            case RenameDto.Table r -> new Rename.OfTable(name(r.from()), name(r.to()));
            case RenameDto.Column r -> new Rename.OfColumn(name(r.table()), r.from(), r.to());
            case RenameDto.EnumValue r -> new Rename.OfEnumValue(name(r.type()), r.from(), r.to());
        };
    }

    static Responses.Operation operation(Operation operation) {
        return new Responses.Operation(
                operation.name(),
                operation.label(),
                operation.lock().map(lock -> lock.sqlName()).orElse(null),
                operation.lock().map(lock -> lock.effect()).orElse(null),
                operation.referencedLock().map(lock -> lock.sqlName()).orElse(null),
                operation.work().name());
    }

    static Responses.Score score(RiskScore score) {
        return new Responses.Score(score.value(), score.level().name(), score.reasons());
    }

    static Responses.Finding finding(Finding finding) {
        List<QualifiedName> referenced = DirectRoute.referencedTables(finding.change());
        return finding(
                finding.rule(),
                finding.message(),
                finding.score(),
                finding.certainty(),
                finding.table(),
                finding.operation(),
                referenced,
                ChangeFormatter.format(finding.change()));
    }

    static Responses.Finding finding(LintFinding finding, Optional<QualifiedName> table) {
        return finding(
                finding.rule(), finding.message(), finding.score(), finding.certainty(), table, finding.operation(), finding.referenced(), null);
    }

    private static Responses.Finding finding(
            RuleInfo rule,
            String message,
            int score,
            double certainty,
            Optional<QualifiedName> table,
            Optional<Operation> operation,
            List<QualifiedName> referenced,
            String change) {
        return new Responses.Finding(
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
                table.map(DtoMapper::name).orElse(null),
                operation.map(DtoMapper::operation).orElse(null),
                operation.flatMap(op -> ReportFormatter.effect(op, table, referenced)).orElse(null),
                change);
    }

    static Responses.Step step(Step step) {
        return new Responses.Step(
                step.sql(),
                step.operation().map(DtoMapper::operation).orElse(null),
                step.table().map(DtoMapper::name).orElse(null),
                step.transactional(),
                step.note(),
                step.cautions());
    }

    static Responses.Unplanned unplanned(MigrationPlan.Unplanned item) {
        return new Responses.Unplanned(ChangeFormatter.format(item.change()), item.reason());
    }

    static Responses.Statement statement(StatementLint statement) {
        return new Responses.Statement(
                statement.span().line(),
                statement.span().column(),
                statement.sql(),
                statement.status().name(),
                statement.table().map(DtoMapper::name).orElse(null),
                statement.operations().stream().map(DtoMapper::operation).toList(),
                statement.findings().stream().map(finding -> finding(finding, statement.table())).toList());
    }

    static Responses.Rule rule(RuleInfo rule, String scope) {
        return new Responses.Rule(
                rule.id(), rule.name(), rule.category().label(), rule.severity().name(), rule.title(), rule.why(), rule.fix(), scope);
    }
}
