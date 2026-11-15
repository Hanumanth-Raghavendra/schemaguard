package io.github.hanumanthraghavendra.schemaguard.engine.planner;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogBuilder;
import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogResult;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real PostgreSQL server as the judge of a plan: run SQL on it, and read back the schema
 * it ends up with.
 *
 * <p>Switched on by the environment variable {@code SG_PG_ORACLE=1}. The server is found
 * through the usual {@code PGHOST}, {@code PGPORT}, {@code PGUSER} and {@code PGPASSWORD};
 * {@code PGBIN} may name the directory of {@code psql} and {@code pg_dump}. Each call uses a
 * database of its own and drops it afterwards.
 *
 * <p>The client tools must be of the server's major version, or the dump may contain syntax
 * the server's version does not have. Where the machine has no such tools, {@code SG_PG_EXEC}
 * can hold a command to put in front of them, for example {@code docker exec -i <container>}
 * to use the ones inside the server's own container. Scripts are passed on standard input, so
 * they need not be visible from there.
 */
final class PostgresOracle {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final String RUN = Long.toString(System.nanoTime(), 36);

    private PostgresOracle() {}

    static boolean available() {
        return "1".equals(System.getenv("SG_PG_ORACLE"));
    }

    /**
     * Runs the scripts in order on an empty database, each statement in its own transaction,
     * and returns the catalog built from the {@code pg_dump} of the result.
     */
    static Catalog schemaAfter(String... scripts) throws IOException, InterruptedException {
        String database = "sg_oracle_" + RUN + "_" + COUNTER.incrementAndGet();
        run(command("psql", "-X", "-q", "-d", "postgres", "-c", "CREATE DATABASE " + database), null);
        try {
            for (String script : scripts) {
                run(command("psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-d", database, "-f", "-"), script);
            }
            String dump = run(command("pg_dump", "--schema-only", "--no-owner", "--no-privileges", "-d", database), null);
            CatalogResult result = CatalogBuilder.fromSql(dump);
            assertThat(result.hasErrors()).as("errors reading the dump:\n%s\n%s", result.diagnostics(), dump).isFalse();
            return result.catalog();
        } finally {
            run(command("psql", "-X", "-q", "-d", "postgres", "-c", "DROP DATABASE " + database), null);
        }
    }

    private static List<String> command(String tool, String... arguments) {
        List<String> command = new ArrayList<>();
        String prefix = System.getenv("SG_PG_EXEC");
        if (prefix != null && !prefix.isBlank()) {
            command.addAll(List.of(prefix.strip().split("\\s+")));
        }
        String bin = System.getenv("PGBIN");
        command.add(bin == null || bin.isBlank() ? tool : Path.of(bin, tool).toString());
        command.addAll(List.of(arguments));
        return command;
    }

    private static String run(List<String> command, String script) throws IOException, InterruptedException {
        Path output = Files.createTempFile("sg-oracle", ".out");
        Path input = Files.createTempFile("sg-oracle", ".sql");
        try {
            Files.writeString(input, script == null ? "" : script, StandardCharsets.UTF_8);
            Process process =
                    new ProcessBuilder(command)
                            .redirectErrorStream(true)
                            .redirectInput(input.toFile())
                            .redirectOutput(output.toFile())
                            .start();
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("%s finished in time", command.get(0)).isTrue();
            String text = Files.readString(output, StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as("PostgreSQL refused:\n%s\n--- script ---\n%s", text, script).isZero();
            return text;
        } finally {
            Files.delete(output);
            Files.delete(input);
        }
    }
}
