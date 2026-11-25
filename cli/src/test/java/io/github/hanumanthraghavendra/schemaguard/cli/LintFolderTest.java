package io.github.hanumanthraghavendra.schemaguard.cli;

import static io.github.hanumanthraghavendra.schemaguard.cli.Cli.run;
import static io.github.hanumanthraghavendra.schemaguard.cli.Cli.write;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.cli.Cli.Run;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code lint --migrations}: each file against the schema the files before it build. */
class LintFolderTest {

    @TempDir
    Path folder;

    /** V1 makes a table, V2 indexes it the risky way (60), V10 adds a column with a lock timeout (0). */
    private void flyway() {
        write(folder, "V1__customers.sql", "CREATE TABLE customers (id bigint PRIMARY KEY, email text NOT NULL);\n");
        write(folder, "V2__email_index.sql", "CREATE INDEX customers_email ON customers (email);\n");
        write(folder, "V10__name.sql", "SET lock_timeout = '5s';\nALTER TABLE customers ADD COLUMN name text;\n");
    }

    @Test
    void everyFileIsCheckedInVersionOrderAgainstTheSchemaBeforeIt() {
        flyway();
        Run run = run("lint", "--migrations", folder.toString());
        assertThat(run.code()).isZero();
        assertThat(run.out()).startsWith("== V1__customers.sql ==\n");
        assertThat(run.out().indexOf("== V2__email_index.sql ==")).isLessThan(run.out().indexOf("== V10__name.sql =="));
        // V10 knows the table V1 made: adding a nullable column to it is no risk.
        Map<?, ?> json = (Map<?, ?>) Json.read(run("lint", "--migrations", folder.toString(), "-f", "json").out());
        List<?> files = (List<?>) json.get("files");
        assertThat(files.stream().map(f -> ((Map<?, ?>) f).get("file")).toList())
                .isEqualTo(List.of("V1__customers.sql", "V2__email_index.sql", "V10__name.sql"));
        assertThat(scores(files)).containsExactly(0, 60, 0);
        assertThat(((Map<?, ?>) json.get("score")).get("value")).as("the worst file").isEqualTo(new BigDecimal(60));
    }

    private static List<Integer> scores(List<?> files) {
        return files.stream()
                .map(f -> ((BigDecimal) ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) f).get("lint")).get("score")).get("value")).intValue())
                .toList();
    }

    @Test
    void namedFilesAreTheOnlyOnesCheckedButEarlierOnesStillBuildTheSchema() {
        flyway();
        Run run = run("lint", "--migrations", folder.toString(), "V10__name.sql", "-f", "json");
        assertThat(run.code()).isZero();
        assertThat(run.err()).as("V10 is understood, so V1 must have been applied first").isEmpty();
        List<?> files = (List<?>) ((Map<?, ?>) Json.read(run.out())).get("files");
        assertThat(files).hasSize(1);
        assertThat(scores(files)).containsExactly(0);

        Run risky = run("lint", "--migrations", folder.toString(), folder.resolve("V2__email_index.sql").toString(),
                "--fail-above", "medium");
        assertThat(risky.code()).as("a path works as well as a name").isEqualTo(1);
        assertThat(risky.err()).isEqualTo("schemaguard: the risk, 60/100 (High), is above --fail-above medium.\n");
    }

    @Test
    void markdownHasASectionPerFile() {
        flyway();
        String markdown = run("lint", "--migrations", folder.toString(), "-f", "markdown").out();
        assertThat(markdown).startsWith("# SchemaGuard migration check\n\nChecked 3 migrations; the riskiest scores 60/100 (High).\n\n## V1__customers.sql\n\n**Risk: 0/100 (None)**\n")
                .contains("## V2__email_index.sql\n\n**Risk: 60/100 (High)**\n")
                .contains("### Line 1\n\n```sql\nCREATE INDEX customers_email ON customers (email)\n```\n\n#### High: Index is built without CONCURRENTLY (SG-R101)");
        String one = run("lint", "--migrations", folder.toString(), "V10__name.sql", "-f", "markdown").out();
        assertThat(one).startsWith("# SchemaGuard migration check\n\n## V10__name.sql\n\n");
    }

    @Test
    void anEarlierFilesErrorsAreShownBecauseLaterChecksAssumeItRan() {
        write(folder, "V1__broken.sql", "ALTER TABLE nowhere ADD COLUMN a text;\nCREATE VIEW v AS SELECT 1;\n");
        write(folder, "V2__table.sql", "CREATE TABLE t (id bigint PRIMARY KEY);\n");
        Run run = run("lint", "--migrations", folder.toString(), "V2__table.sql", "--fail-above", "high");
        assertThat(run.code()).as("only the checked file decides").isZero();
        assertThat(run.err()).contains("V1__broken.sql:1:1: error: ").doesNotContain("warning");
    }

    @Test
    void aCheckedFileThatPostgresWouldRefuseFailsWithALimit() {
        write(folder, "V1__table.sql", "CREATE TABLE t (id bigint PRIMARY KEY);\n");
        write(folder, "V2__oops.sql", "ALTER TABLE nowhere ADD COLUMN a text;\n");
        Run run = run("lint", "--migrations", folder.toString(), "--fail-above", "high");
        assertThat(run.code()).isEqualTo(1);
        assertThat(run.err()).endsWith("schemaguard: PostgreSQL would refuse statements in V2__oops.sql (1).\n");
    }

    @Test
    void flywaysRepeatableAndUndoFilesAreSkippedAndNamed() {
        flyway();
        write(folder, "R__views.sql", "CREATE VIEW v AS SELECT 1;\n");
        write(folder, "U2__undo_index.sql", "DROP INDEX customers_email;\n");
        write(folder, "notes.txt", "not SQL\n");
        Run run = run("lint", "--migrations", folder.toString(), "-f", "json");
        assertThat(run.code()).isZero();
        assertThat(run.err()).isEqualTo("""
                schemaguard: skipped R__views.sql (Flyway repeatable and undo migrations are not checked)
                schemaguard: skipped U2__undo_index.sql (Flyway repeatable and undo migrations are not checked)
                """);
        assertThat((List<?>) ((Map<?, ?>) Json.read(run.out())).get("files")).hasSize(3);
    }

    @Test
    void otherNamesRunInNameOrderWithNumbersComparedAsNumbers() {
        write(folder, "10_index.sql", "CREATE INDEX t_a ON t (a);\n");
        write(folder, "2_table.sql", "CREATE TABLE t (id bigint PRIMARY KEY, a text);\n");
        Run run = run("lint", "--migrations", folder.toString());
        assertThat(run.code()).isZero();
        assertThat(run.out()).startsWith("== 2_table.sql ==");
        assertThat(run.err()).as("the index finds the table").isEmpty();
    }

    @Test
    void folderProblemsAreInputErrors() throws IOException {
        assertThat(run("lint", "--migrations", folder.toString()).err()).isEqualTo("schemaguard: " + folder + " has no .sql migrations.\n");
        assertThat(run("lint", "--migrations", folder.resolve("nope").toString()).err())
                .isEqualTo("schemaguard: " + folder.resolve("nope") + " is not a folder.\n");

        write(folder, "V1__a.sql", "CREATE TABLE a (id int);\n");
        write(folder, "V1.0__b.sql", "CREATE TABLE b (id int);\n");
        Run duplicate = run("lint", "--migrations", folder.toString());
        assertThat(duplicate.code()).isEqualTo(2);
        assertThat(duplicate.err()).isIn(
                "schemaguard: Two migrations have version 1.0: V1__a.sql and V1.0__b.sql.\n",
                "schemaguard: Two migrations have version 1: V1.0__b.sql and V1__a.sql.\n");
        Files.delete(folder.resolve("V1.0__b.sql"));

        write(folder, "002_b.sql", "CREATE TABLE b (id int);\n");
        assertThat(run("lint", "--migrations", folder.toString()).err())
                .isEqualTo("schemaguard: " + folder + " mixes Flyway files (V1__a.sql) with others (002_b.sql); the order they run in is unclear.\n");
        Files.delete(folder.resolve("002_b.sql"));

        Run missing = run("lint", "--migrations", folder.toString(), "V9__later.sql");
        assertThat(missing.code()).isEqualTo(2);
        assertThat(missing.err()).isEqualTo("schemaguard: V9__later.sql is not a migration in " + folder + ".\n");
    }

    /** SchemaGuard's own Flyway migrations pass its own check, as the pipeline will run it (Phase 8.3). */
    @Test
    void schemaGuardsOwnMigrationsPassItsOwnCheck() {
        Run run = run("lint", "--migrations", System.getProperty("schemaguard.migrations"), "--fail-above", "low");
        assertThat(run.err()).isEmpty();
        assertThat(run.code()).isZero();
        assertThat(run.out()).contains("== V1__", "== V4__api_keys.sql ==");
    }
}
