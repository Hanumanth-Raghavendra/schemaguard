package io.github.hanumanthraghavendra.schemaguard.api.service;

import io.github.hanumanthraghavendra.schemaguard.api.config.ApiProperties;
import io.github.hanumanthraghavendra.schemaguard.api.dto.Requests;
import io.github.hanumanthraghavendra.schemaguard.api.dto.Responses;
import io.github.hanumanthraghavendra.schemaguard.engine.diagram.ErModel;
import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogBuilder;
import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogResult;
import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Severity;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.ChangeFormatter;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.SchemaDiff;
import io.github.hanumanthraghavendra.schemaguard.engine.lint.LintFormatter;
import io.github.hanumanthraghavendra.schemaguard.engine.lint.LintReport;
import io.github.hanumanthraghavendra.schemaguard.engine.lint.MigrationLinter;
import io.github.hanumanthraghavendra.schemaguard.engine.lint.ScriptRules;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.Parser;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserOptions;
import io.github.hanumanthraghavendra.schemaguard.engine.planner.MigrationPlan;
import io.github.hanumanthraghavendra.schemaguard.engine.planner.PlanOptions;
import io.github.hanumanthraghavendra.schemaguard.engine.planner.Planner;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ReportFormatter;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskAnalyzer;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskReport;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * The four analyses the API offers. Each reads its SQL inputs into catalogs, refuses inputs
 * that are too large or that PostgreSQL would refuse, runs the engine and maps the result.
 * Nothing is stored and nothing of the input is logged.
 */
@Service
public class AnalysisService {

    private final ApiProperties.Limits limits;

    public AnalysisService(ApiProperties properties) {
        this.limits = properties.limits();
    }

    public Responses.Diff diff(Requests.Compare request) {
        Parsed before = parse("before", request.before());
        Parsed after = parse("after", request.after());
        SchemaDiff diff = SchemaDiff.compare(before.catalog(), after.catalog());
        return new Responses.Diff(
                diff.changes().stream().map(DtoMapper::change).toList(),
                diff.significant().size(),
                warnings(before, after),
                ChangeFormatter.format(diff));
    }

    public Responses.Model model(Requests.Compare request) {
        Parsed before = parse("before", request.before());
        Parsed after = parse("after", request.after());
        ErModel model = ErModel.of(SchemaDiff.compare(before.catalog(), after.catalog()));
        return new Responses.Model(
                model.tables().stream().map(DtoMapper::table).toList(),
                model.relationships().stream().map(DtoMapper::reference).toList(),
                warnings(before, after));
    }

    public Responses.Report report(Requests.Compare request) {
        Parsed before = parse("before", request.before());
        Parsed after = parse("after", request.after());
        RiskReport report = RiskAnalyzer.analyze(SchemaDiff.compare(before.catalog(), after.catalog()));
        return new Responses.Report(
                DtoMapper.score(report.score()),
                report.findings().stream().map(DtoMapper::finding).toList(),
                warnings(before, after),
                ReportFormatter.format(report));
    }

    public Responses.Plan plan(Requests.Plan request) {
        Parsed before = parse("before", request.before());
        Parsed after = parse("after", request.after());
        PlanOptions options = request.route() == Requests.Route.DIRECT ? PlanOptions.direct() : PlanOptions.safe();
        if (request.renames() != null) {
            options = options.withRenames(request.renames().stream().map(DtoMapper::rename).toList());
        }
        if (request.lockTimeout() != null) {
            options = withLockTimeout(options, request.lockTimeout());
        }
        MigrationPlan plan;
        try {
            plan = Planner.plan(before.catalog(), after.catalog(), options);
        } catch (IllegalArgumentException e) {
            throw new InputRejectedException(e.getMessage(), List.of());
        }
        return new Responses.Plan(
                plan.route().name(),
                plan.isComplete(),
                plan.needsDecisions(),
                plan.steps().stream().map(DtoMapper::step).toList(),
                plan.unplanned().stream().map(DtoMapper::unplanned).toList(),
                warnings(before, after),
                plan.toSql());
    }

    public Responses.Lint lint(Requests.Lint request) {
        Parsed schema = parse("schema", request.schema());
        checkLength("migration", request.migration());
        int statements = Parser.parse(request.migration()).statements().size();
        if (statements > limits.maxStatements()) {
            throw new LimitExceededException(
                    "The migration has " + statements + " statements; at most " + limits.maxStatements() + " are accepted.");
        }
        LintReport report = MigrationLinter.lint(schema.catalog(), request.migration());
        return new Responses.Lint(
                DtoMapper.score(report.score()),
                report.statements().stream().map(DtoMapper::statement).toList(),
                report.diagnostics().stream().map(d -> DtoMapper.diagnostic("migration", d)).toList(),
                schema.warnings(),
                LintFormatter.format(report));
    }

    /** Every rule, schema rules first, each list ordered by id. */
    public List<Responses.Rule> rules() {
        List<Responses.Rule> rules = new ArrayList<>();
        RuleRegistry.standard().rules().forEach(rule -> rules.add(DtoMapper.rule(rule.info(), "schema")));
        ScriptRules.ALL.forEach(rule -> rules.add(DtoMapper.rule(rule, "script")));
        return rules;
    }

    // ---------------------------------------------------------------- inputs

    /** A schema read from SQL, with the warnings about it (statements the engine skipped). */
    private record Parsed(Catalog catalog, List<Responses.Diagnostic> warnings) {}

    private Parsed parse(String input, String sql) {
        checkLength(input, sql);
        CatalogResult result = CatalogBuilder.fromSql(sql, ParserOptions.defaults());
        List<Responses.Diagnostic> all = result.diagnostics().stream().map(d -> DtoMapper.diagnostic(input, d)).toList();
        if (result.hasErrors()) {
            long errors = result.diagnostics().stream().filter(d -> d.severity() == Severity.ERROR).count();
            throw new InputRejectedException(
                    "The " + input + " schema has " + errors + (errors == 1 ? " error" : " errors") + " and cannot be analysed.",
                    all);
        }
        return new Parsed(result.catalog(), all);
    }

    private void checkLength(String input, String sql) {
        if (sql.length() > limits.maxInputChars()) {
            throw new LimitExceededException(
                    "The " + input + " input has " + sql.length() + " characters; at most " + limits.maxInputChars() + " are accepted.");
        }
    }

    private static List<Responses.Diagnostic> warnings(Parsed before, Parsed after) {
        return Stream.concat(before.warnings().stream(), after.warnings().stream()).toList();
    }

    private static PlanOptions withLockTimeout(PlanOptions options, String lockTimeout) {
        try {
            return options.withLockTimeout(lockTimeout);
        } catch (IllegalArgumentException e) {
            throw new InputRejectedException(e.getMessage(), List.of());
        }
    }

}
