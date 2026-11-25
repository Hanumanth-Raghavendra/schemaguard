package io.github.hanumanthraghavendra.schemaguard.cli;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.SchemaDiff;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskAnalyzer;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "report", description = "Score the risk of changing one schema into another, if done directly.")
final class ReportCommand extends AnalysisCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<before.sql>", description = "The schema now (- for standard input)")
    String before;

    @Parameters(index = "1", paramLabel = "<after.sql>", description = "The schema wanted")
    String after;

    @Option(
            names = "--fail-above",
            paramLabel = "<limit>",
            converter = Threshold.Converter.class,
            description = "Exit with 1 when the risk is above this: a score from 0 to 100, or none, low, medium or high")
    Threshold failAbove;

    @Override
    public Integer call() {
        Source[] sources = read(before, after);
        Schemas.Parsed old = Schemas.parse("before", sources[0], io().err());
        Schemas.Parsed wanted = Schemas.parse("after", sources[1], io().err());
        SchemaDiff changes = SchemaDiff.compare(old.catalog(), wanted.catalog());
        Documents.ReportResponse report = Documents.report(RiskAnalyzer.analyze(changes), Schemas.warnings(old, wanted));
        print(report::text, () -> Markdown.report(report, Documents.diff(changes, report.warnings())), () -> report);
        return verdict(failAbove, report.score());
    }
}
