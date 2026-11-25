package io.github.hanumanthraghavendra.schemaguard.cli;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Runs the CLI in this process, with its output captured and an environment of the test's choosing. */
final class Cli {

    /** The engine's reviewed fixtures, which its golden tests also use. */
    static final Path GOLDEN = Path.of("../engine/src/test/resources/golden/evolution");

    private Cli() {}

    record Run(int code, String out, String err) {}

    static Run run(String... args) {
        return run(Map.of(), "", args);
    }

    static Run run(Map<String, String> env, String stdin, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Io io = new Io(
                new PrintWriter(out),
                new PrintWriter(err),
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                env);
        int code = SchemaGuardCli.run(args, io);
        return new Run(code, out.toString(), err.toString());
    }

    static String golden(String name) {
        return GOLDEN.resolve(name).toString();
    }

    static String read(String name) {
        try {
            return Files.readString(GOLDEN.resolve(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Path write(Path folder, String name, String content) {
        try {
            return Files.writeString(folder.resolve(name), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
