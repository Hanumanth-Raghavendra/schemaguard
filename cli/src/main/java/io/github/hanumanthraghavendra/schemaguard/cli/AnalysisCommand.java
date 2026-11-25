package io.github.hanumanthraghavendra.schemaguard.cli;

import java.util.function.Supplier;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/** What every analysis command shares: the output format and how a result is printed. */
abstract class AnalysisCommand {

    @ParentCommand
    SchemaGuardCli root;

    @Spec
    CommandSpec spec;

    @Option(
            names = {"-f", "--format"},
            paramLabel = "<format>",
            defaultValue = "text",
            description = "text (the default), markdown or json")
    Format format;

    Io io() {
        return root.io();
    }

    /** Reads the inputs; at most one of them may be standard input. */
    Source[] read(String... paths) {
        long stdin = java.util.Arrays.stream(paths).filter(Source.STDIN::equals).count();
        if (stdin > 1) {
            throw new ParameterException(spec.commandLine(), "Only one input can be read from standard input (-).");
        }
        Source[] sources = new Source[paths.length];
        for (int i = 0; i < paths.length; i++) {
            sources[i] = Source.read(paths[i], io());
        }
        return sources;
    }

    void print(Supplier<String> text, Supplier<String> markdown, Supplier<Object> json) {
        String output =
                switch (format) {
                    case TEXT -> text.get();
                    case MARKDOWN -> markdown.get();
                    case JSON -> Json.write(json.get());
                };
        io().out().print(output.stripTrailing() + "\n");
    }

    /** Exit code 1 and a line on standard error when the score is above {@code --fail-above}. */
    int verdict(Threshold threshold, Documents.Score score) {
        if (threshold == null || !threshold.exceeded(score.value())) {
            return ExitCodes.OK;
        }
        io().err().println("schemaguard: the risk, " + score.value() + "/100 (" + Markdown.sentenceCase(score.level())
                + "), is above --fail-above " + threshold.text() + ".");
        return ExitCodes.RISK_ABOVE_LIMIT;
    }
}
