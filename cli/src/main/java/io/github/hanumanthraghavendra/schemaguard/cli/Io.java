package io.github.hanumanthraghavendra.schemaguard.cli;

import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Map;

/**
 * Where the CLI writes, what it reads as standard input, and the environment it sees. The
 * real process passes its own; tests pass buffers and a made-up environment.
 */
record Io(PrintWriter out, PrintWriter err, InputStream in, Map<String, String> env) {

    static Io system() {
        return new Io(
                new PrintWriter(new OutputStreamWriter(System.out, System.out.charset())),
                new PrintWriter(new OutputStreamWriter(System.err, System.err.charset())),
                System.in,
                System.getenv());
    }
}
