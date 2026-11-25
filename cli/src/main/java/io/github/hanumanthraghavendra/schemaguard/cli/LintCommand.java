package io.github.hanumanthraghavendra.schemaguard.cli;

import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic;
import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Severity;
import io.github.hanumanthraghavendra.schemaguard.engine.lint.LintReport;
import io.github.hanumanthraghavendra.schemaguard.engine.lint.MigrationLinter;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;

@Command(
        name = "lint",
        description = {
            "Check a migration script, statement by statement, against the schema it runs on.",
            "With --migrations, check a folder of migrations: each file against the schema the files before it build."
        })
final class LintCommand extends AnalysisCommand implements Callable<Integer> {

    @Option(names = "--migrations", paramLabel = "<folder>", description = "A folder of migrations, such as Flyway's db/migration")
    Path folder;

    @Parameters(
            paramLabel = "<file>",
            arity = "0..*",
            description = {
                "Without --migrations: <schema.sql> <migration.sql> (either may be - for standard input).",
                "With --migrations: which files of the folder to check (default: all of them)."
            })
    List<String> files = new ArrayList<>();

    @Option(
            names = "--fail-above",
            paramLabel = "<limit>",
            converter = Threshold.Converter.class,
            description = {
                "Exit with 1 when the risk is above this (a score from 0 to 100, or none, low, medium or high),",
                "or when a checked migration has a statement PostgreSQL would refuse"
            })
    Threshold failAbove;

    @Override
    public Integer call() {
        return folder == null ? script() : folder();
    }

    private int script() {
        if (files.size() != 2) {
            throw new ParameterException(spec.commandLine(), "Give <schema.sql> and <migration.sql>, or --migrations <folder>.");
        }
        Source[] sources = read(files.get(0), files.get(1));
        Schemas.Parsed schema = Schemas.parse("schema", sources[0], io().err());
        LintReport report = MigrationLinter.lint(schema.catalog(), sources[1].sql());
        report.diagnostics().forEach(d -> io().err().println(Schemas.line(sources[1].name(), d)));
        Documents.LintResponse lint = Documents.lint(report, schema.warnings());
        print(lint::text, () -> Markdown.lint(lint), () -> lint);
        return verdict(List.of(new Checked(sources[1].name(), report)), lint.score());
    }

    private int folder() {
        Migrations.Folder migrations = Migrations.read(folder);
        migrations.skipped().forEach(name ->
                io().err().println("schemaguard: skipped " + name + " (Flyway repeatable and undo migrations are not checked)"));
        Set<String> wanted = wanted(migrations.files());

        Catalog schema = Catalog.empty();
        List<Checked> checked = new ArrayList<>();
        for (Path file : migrations.files()) {
            if (checked.size() == wanted.size()) {
                break;
            }
            String name = file.getFileName().toString();
            Source source = Source.read(file.toString(), io());
            LintReport report = MigrationLinter.lint(schema, source.sql());
            if (wanted.contains(name)) {
                report.diagnostics().forEach(d -> io().err().println(Schemas.line(source.name(), d)));
                checked.add(new Checked(name, report));
            } else {
                // An earlier file only builds the schema. Its errors matter (later checks assume it ran);
                // its warnings, already seen when it was checked, would only repeat on every run.
                errors(report).forEach(d -> io().err().println(Schemas.line(source.name(), d)));
            }
            schema = report.result();
        }

        List<Documents.CheckedFile> files = checked.stream()
                .map(c -> new Documents.CheckedFile(c.name(), Documents.lint(c.report(), List.of())))
                .toList();
        Documents.Score worst = files.stream()
                .map(f -> f.lint().score())
                .max(Comparator.comparingInt(Documents.Score::value))
                .orElseThrow();
        Documents.MigrationsResponse response = new Documents.MigrationsResponse(worst, files);
        print(
                () -> files.stream().map(f -> "== " + f.file() + " ==\n" + f.lint().text().stripTrailing()).collect(Collectors.joining("\n\n")),
                () -> Markdown.migrations(response),
                () -> response);
        return verdict(checked, worst);
    }

    /** The folder's files to check, in the folder's order; every name given must be one of them. */
    private Set<String> wanted(List<Path> ordered) {
        List<String> names = ordered.stream().map(p -> p.getFileName().toString()).toList();
        if (files.isEmpty()) {
            return new LinkedHashSet<>(names);
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String given : files) {
            String name = Path.of(given).getFileName().toString();
            if (!names.contains(name)) {
                throw new InputException(given + " is not a migration in " + folder + ".");
            }
            wanted.add(name);
        }
        return wanted;
    }

    private record Checked(String name, LintReport report) {}

    private static List<Diagnostic> errors(LintReport report) {
        return report.diagnostics().stream().filter(d -> d.severity() == Severity.ERROR).toList();
    }

    /** With {@code --fail-above}, a statement PostgreSQL would refuse fails the check as a risk above the limit does. */
    private int verdict(List<Checked> checked, Documents.Score score) {
        if (failAbove == null) {
            return ExitCodes.OK;
        }
        List<String> refused = checked.stream()
                .filter(c -> !errors(c.report()).isEmpty())
                .map(c -> c.name() + " (" + errors(c.report()).size() + ")")
                .toList();
        if (!refused.isEmpty()) {
            io().err().println("schemaguard: PostgreSQL would refuse statements in " + String.join(", ", refused) + ".");
            return ExitCodes.RISK_ABOVE_LIMIT;
        }
        return verdict(failAbove, score);
    }
}
