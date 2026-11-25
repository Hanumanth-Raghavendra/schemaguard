package io.github.hanumanthraghavendra.schemaguard.cli;

import static io.github.hanumanthraghavendra.schemaguard.cli.Cli.golden;
import static io.github.hanumanthraghavendra.schemaguard.cli.Cli.read;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AnalysisCommandsTest {

    private static final String TABLE = "CREATE TABLE t (id integer PRIMARY KEY, a text);\n";
    private static final String INDEX = "CREATE INDEX t_a ON t (a);\n";

    @TempDir
    Path dir;

    private String file(String name, String sql) {
        return write(dir, name, sql).toString();
    }

    // ---------------------------------------------------------------- the engine's reviewed output

    @Test
    void textOutputIsTheEnginesReviewedGoldenOutput() {
        String before = golden("before_pg_dump_16.sql");
        String after = golden("after_pg_dump_16.sql");
        assertThat(run("diff", before, after).out()).isEqualTo(read("expected-diff.txt").stripTrailing() + "\n");
        assertThat(run("report", before, after).out()).isEqualTo(read("expected-report.txt").stripTrailing() + "\n");
        assertThat(run("plan", "--route", "direct", before, after).out())
                .isEqualTo(read("expected-plan-direct.sql").stripTrailing() + "\n");
        assertThat(run("lint", golden("before.sql"), golden("migration.sql")).out())
                .isEqualTo(read("expected-lint.txt").stripTrailing() + "\n");
    }

    @Test
    void aReportExitsZeroWithoutALimitHoweverRiskyTheChange() {
        Run run = run("report", golden("before_pg_dump_16.sql"), golden("after_pg_dump_16.sql"));
        assertThat(run.code()).isZero();
        assertThat(run.err()).isEmpty();
    }

    // ---------------------------------------------------------------- --fail-above

    @ParameterizedTest(name = "risk 60 with --fail-above {0} exits {1}")
    @CsvSource({"59, 1", "60, 0", "0, 1", "100, 0", "none, 1", "low, 1", "medium, 1", "high, 0", "HIGH, 0", "' 49 ', 1"})
    void failAboveComparesTheScoreWithTheLimit(String limit, int code) {
        Run run = run("report", file("before.sql", TABLE), file("after.sql", TABLE + INDEX), "--fail-above", limit);
        assertThat(run.code()).isEqualTo(code);
        if (code == 1) {
            assertThat(run.err()).isEqualTo("schemaguard: the risk, 60/100 (High), is above --fail-above "
                    + limit.trim().toLowerCase() + ".\n");
        } else {
            assertThat(run.err()).isEmpty();
        }
        assertThat(run.out()).as("the report is printed either way").contains("SG-R101");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '"',
            value = {
                "critical | nothing is above critical; use high to fail on critical risks only",
                "101      | '101' is neither a score from 0 to 100 nor one of none, low, medium, high",
                "-1       | '-1' is neither",
                "lots     | 'lots' is neither"
            })
    void aLimitThatMeansNothingIsAUsageError(String limit, String message) {
        Run run = run("report", file("a.sql", TABLE), file("b.sql", TABLE), "--fail-above", limit);
        assertThat(run.code()).isEqualTo(2);
        assertThat(run.err()).contains("Invalid value for option '--fail-above'", message);
        assertThat(run.out()).isEmpty();
    }

    @Test
    void lintFailsAboveTheLimitToo() {
        Run run = run("lint", file("schema.sql", TABLE), file("migration.sql", INDEX), "--fail-above", "medium");
        assertThat(run.code()).isEqualTo(1);
        assertThat(run.err()).isEqualTo("schemaguard: the risk, 60/100 (High), is above --fail-above medium.\n");
        assertThat(run("lint", file("s.sql", TABLE), file("m.sql", INDEX), "--fail-above", "high").code()).isZero();
    }

    @Test
    void lintWithALimitFailsWhenPostgresWouldRefuseAStatementWhateverTheScore() {
        Run run = run("lint", file("schema.sql", TABLE), file("migration.sql", "ALTER TABLE missing ADD COLUMN b text;\n"),
                "--fail-above", "high");
        assertThat(run.code()).isEqualTo(1);
        assertThat(run.err()).contains("migration.sql:1:", ": error: ").endsWith(
                "schemaguard: PostgreSQL would refuse statements in " + dir.resolve("migration.sql") + " (1).\n");
        assertThat(run("lint", file("s.sql", TABLE), file("m.sql", "ALTER TABLE missing ADD COLUMN b text;\n")).code())
                .as("without a limit the check only reports")
                .isZero();
    }

    // ---------------------------------------------------------------- formats

    @Test
    void markdownIsTheWebAppsReportLayout() {
        Run run = run("report", file("before.sql", TABLE), file("after.sql", TABLE + INDEX), "--format", "markdown");
        assertThat(run.out()).isEqualTo(String.join("\n",
                "# SchemaGuard risk report",
                "",
                "**Risk: 60/100 (High)**",
                "",
                "Main reasons:",
                "",
                "- Index is built without CONCURRENTLY (SG-R101, 60 points)",
                "",
                "## Risks",
                "",
                "### High: Index is built without CONCURRENTLY (SG-R101)",
                "",
                line(run.out(), 12),
                "",
                "- Change: `+ index public.t_a ON public.t USING btree (a)`",
                "- Lock: SHARE, blocks writes, reads continue",
                "",
                line(run.out(), 17),
                "",
                line(run.out(), 19),
                "",
                "## Changes",
                "",
                "- `+ index public.t_a ON public.t USING btree (a)`",
                ""));
        assertThat(line(run.out(), 17)).startsWith("Why: ");
        assertThat(line(run.out(), 19)).startsWith("Instead: ");
    }

    private static String line(String text, int index) {
        return text.split("\n", -1)[index];
    }

    @Test
    void markdownForTheOtherCommands() {
        String before = file("before.sql", TABLE);
        String after = file("after.sql", TABLE + INDEX);
        assertThat(run("diff", before, before, "-f", "markdown").out())
                .isEqualTo("# SchemaGuard schema changes\n\nThe schemas are the same.\n");
        assertThat(run("diff", before, after, "-f", "markdown").out()).isEqualTo(
                "# SchemaGuard schema changes\n\n## Changes\n\n- `+ index public.t_a ON public.t USING btree (a)`\n");
        assertThat(run("plan", before, before, "-f", "markdown").out())
                .isEqualTo("# SchemaGuard migration plan\n\n## Plan (safe route)\n\nNothing to run.\n");
        assertThat(run("plan", before, after, "-f", "markdown").out())
                .startsWith("# SchemaGuard migration plan\n\n## Plan (safe route)\n\n```sql\n-- SchemaGuard migration plan, safe route")
                .contains("CREATE INDEX CONCURRENTLY t_a ON public.t USING btree (a);")
                .endsWith("```\n");
        assertThat(run("lint", before, file("m.sql", "SET lock_timeout = '5s';\n" + INDEX), "-f", "markdown").out())
                .startsWith("# SchemaGuard migration check\n\n**Risk: 60/100 (High)**\n")
                .contains("## Line 1\n\n```sql\nSET lock_timeout = '5s'\n```\n\nStatus: Not schema\n")
                .contains("## Line 2\n\n```sql\nCREATE INDEX t_a ON t (a)\n```\n\n### High: Index is built without CONCURRENTLY (SG-R101)");
    }

    @Test
    void markdownShowsWhatPostgresWouldRefuseUnderItsStatement() {
        String markdown = run("lint", file("s.sql", TABLE), file("m.sql", INDEX + "ALTER TABLE missing ADD COLUMN b text;\n"),
                "-f", "markdown").out();
        assertThat(markdown).contains("## Line 2\n\n```sql\nALTER TABLE missing ADD COLUMN b text\n```\n\nStatus: Would fail\n\nError: ");
        assertThat(markdown.indexOf("Error: ")).as("under line 2, not line 1").isGreaterThan(markdown.indexOf("## Line 2"));
    }

    @Test
    void aPlanNamesWhatItCannotPlan() {
        String before = file("before.sql", "CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy');\n");
        String after = file("after.sql", "CREATE TYPE mood AS ENUM ('sad', 'happy');\n");
        Run run = run("plan", before, after, "-f", "markdown");
        assertThat(run.out()).contains("- Not in the plan: `~ enum public.mood: value 'ok' removed`: ");
        Map<?, ?> json = (Map<?, ?>) Json.read(run("plan", before, after, "-f", "json").out());
        assertThat(json.get("complete")).isEqualTo(false);
    }

    @Test
    void jsonIsTheApisResponseBody() {
        Map<?, ?> report = (Map<?, ?>) Json.read(run("report", file("b.sql", TABLE), file("a.sql", TABLE + INDEX), "--format", "JSON").out());
        assertThat(List.copyOf(report.keySet())).isEqualTo(List.of("score", "findings", "warnings", "text"));
        assertThat(((Map<?, ?>) report.get("score")).get("value")).isEqualTo(new BigDecimal(60));
        List<?> findings = (List<?>) report.get("findings");
        Map<?, ?> finding = (Map<?, ?>) findings.get(0);
        assertThat(finding.get("ruleId")).isEqualTo("SG-R101");
        assertThat(finding.get("table")).isEqualTo(Map.of("schema", "public", "name", "t"));
        assertThat(((Map<?, ?>) finding.get("operation")).get("lock")).isEqualTo("SHARE");
    }

    @Test
    void lockTimeoutGoesIntoTheSafePlan() {
        Run run = run("plan", file("b.sql", TABLE), file("a.sql", TABLE + "ALTER TABLE t ADD CONSTRAINT a_set CHECK (a <> '');\n"),
                "--lock-timeout", "3s");
        assertThat(run.code()).isZero();
        assertThat(run.out()).contains("SET lock_timeout = '3s';");
        Run bad = run("plan", file("b2.sql", TABLE), file("a2.sql", TABLE + INDEX), "--lock-timeout", "soon");
        assertThat(bad.code()).isEqualTo(2);
        assertThat(bad.err()).startsWith("schemaguard: ").contains("soon");
    }

    // ---------------------------------------------------------------- inputs

    @Test
    void oneInputMayComeFromStandardInput() {
        Run run = run(Map.of(), TABLE + INDEX, "diff", file("before.sql", TABLE), "-");
        assertThat(run.code()).isZero();
        assertThat(run.out()).isEqualTo("+ index public.t_a ON public.t USING btree (a)\n");
        Run both = run(Map.of(), TABLE, "diff", "-", "-");
        assertThat(both.code()).isEqualTo(2);
        assertThat(both.err()).startsWith("Only one input can be read from standard input (-).");
    }

    @Test
    void inputThatCannotBeReadIsExplainedWithExitCodeTwo() throws IOException {
        String good = file("good.sql", TABLE);
        assertThat(run("diff", good, dir.resolve("missing.sql").toString()))
                .isEqualTo(new Run(2, "", "schemaguard: " + dir.resolve("missing.sql") + ": no such file.\n"));
        assertThat(run("diff", good, dir.toString()).err()).isEqualTo("schemaguard: " + dir + " is a folder, not a SQL file.\n");
        Path latin1 = Files.write(dir.resolve("latin1.sql"), new byte[] {'-', '-', ' ', (byte) 0xE9, '\n'});
        assertThat(run("diff", good, latin1.toString()).err()).isEqualTo("schemaguard: " + latin1 + " is not UTF-8 text.\n");
    }

    @Test
    void aByteOrderMarkIsIgnored() {
        assertThat(run("diff", file("bom.sql", "﻿" + TABLE), file("plain.sql", TABLE)).out()).isEqualTo("\n");
    }

    @Test
    void schemaErrorsStopTheAnalysisAndPointAtTheLine() {
        String bad = file("bad.sql", "CREATE TABLE t (id integer PRIMARY KEY, id integer);\n");
        Run run = run("report", bad, file("good.sql", TABLE));
        assertThat(run.code()).isEqualTo(2);
        assertThat(run.out()).isEmpty();
        assertThat(run.err()).isEqualTo("schemaguard: The before schema (" + bad + ") has 1 error and cannot be analysed:\n"
                + bad + ":1:41: error: Column \"id\" is specified more than once [SG-PARSE-002]\n");
    }

    @Test
    void warningsGoToStandardErrorAndIntoTheJson() {
        String view = file("view.sql", TABLE + "CREATE VIEW v AS SELECT 1;\n");
        Run run = run("diff", file("b.sql", TABLE), view, "-f", "json");
        assertThat(run.code()).isZero();
        assertThat(run.err()).isEqualTo(view + ":2:1: warning: Unsupported statement: CREATE VIEW. It was not analysed. [SG-PARSE-001]\n");
        Map<?, ?> warning = (Map<?, ?>) ((List<?>) ((Map<?, ?>) Json.read(run.out())).get("warnings")).get(0);
        assertThat(warning.get("input")).isEqualTo("after");
        assertThat(warning.get("line")).isEqualTo(new BigDecimal(2));
    }

    // ---------------------------------------------------------------- the command itself

    @Test
    void withoutACommandItShowsTheUsageAndExitsTwo() {
        Run run = run();
        assertThat(run.code()).isEqualTo(2);
        assertThat(run.err()).contains("Usage: schemaguard", "diff", "report", "plan", "lint", "save", "Exit codes:");
    }

    @Test
    void helpAndVersion() {
        Run help = run("report", "--help");
        assertThat(help.code()).isZero();
        assertThat(help.out()).contains("Usage: schemaguard report", "--fail-above=<limit>", "--format=<format>");
        Run version = run("--version");
        assertThat(version.code()).isZero();
        assertThat(version.out()).startsWith("SchemaGuard 0.").doesNotContain("${").contains("Java ");
        assertThat(run("help", "lint").out()).contains("--migrations=<folder>");
    }

    @Test
    void usageErrorsExitTwo() {
        assertThat(run("analyse").code()).isEqualTo(2);
        assertThat(run("diff", "only-one.sql").err()).contains("Missing required parameter: '<after.sql>'");
        assertThat(run("report", "a.sql", "b.sql", "--format", "yaml").err()).contains("Invalid value for option '--format'");
        Run lint = run("lint", file("only.sql", TABLE));
        assertThat(lint.code()).isEqualTo(2);
        assertThat(lint.err()).startsWith("Give <schema.sql> and <migration.sql>, or --migrations <folder>.");
    }
}
