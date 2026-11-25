package io.github.hanumanthraghavendra.schemaguard.cli;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.SchemaDiff;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "diff", description = "List every difference between two schemas, with likely renames.")
final class DiffCommand extends AnalysisCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<before.sql>", description = "The schema now (- for standard input)")
    String before;

    @Parameters(index = "1", paramLabel = "<after.sql>", description = "The schema wanted")
    String after;

    @Override
    public Integer call() {
        Source[] sources = read(before, after);
        Schemas.Parsed old = Schemas.parse("before", sources[0], io().err());
        Schemas.Parsed wanted = Schemas.parse("after", sources[1], io().err());
        Documents.DiffResponse diff =
                Documents.diff(SchemaDiff.compare(old.catalog(), wanted.catalog()), Schemas.warnings(old, wanted));
        print(diff::text, () -> Markdown.diff(diff), () -> diff);
        return ExitCodes.OK;
    }
}
