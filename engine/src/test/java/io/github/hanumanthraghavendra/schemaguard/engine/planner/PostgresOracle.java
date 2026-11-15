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
        run(List.of(tool("psql"), "-X", "-q", "-d", "postgres", "-c", "CREATE DATABASE " + database), null);
        try {
            for (String script : scripts) {
                Path file = Files.createTempFile("sg-oracle", ".sql");
                try {
                    Files.writeString(file, script, StandardCharsets.UTF_8);
                    run(List.of(tool("psql"), "-X", "-q", "-v", "ON_ERROR_STOP=1", "-d", database, "-f", file.toString()), script);
                } finally {
                    Files.delete(file);
                }
            }
            String dump = run(List.of(tool("pg_dump"), "--schema-only", "--no-owner", "--no-privileges", "-d", database), null);
            CatalogResult result = CatalogBuilder.fromSql(dump);
            assertThat(result.hasErrors()).as("errors reading the dump:\n%s\n%s", result.diagnostics(), dump).isFalse();
            return result.catalog();
        } finally {
            run(List.of(tool("psql"), "-X", "-q", "-d", "postgres", "-c", "DROP DATABASE " + database), null);
        }
    }

    private static String tool(String name) {
        String bin = System.getenv("PGBIN");
        return bin == null || bin.isBlank() ? name : Path.of(bin, name).toString();
    }

    private static String run(List<String> command, String script) throws IOException, InterruptedException {
        Path output = Files.createTempFile("sg-oracle", ".out");
        try {
            Process process = new ProcessBuilder(new ArrayList<>(command)).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("%s finished in time", command.get(0)).isTrue();
            String text = Files.readString(output, StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as("PostgreSQL refused:\n%s\n--- script ---\n%s", text, script).isZero();
            return text;
        } finally {
            Files.delete(output);
        }
    }
}
