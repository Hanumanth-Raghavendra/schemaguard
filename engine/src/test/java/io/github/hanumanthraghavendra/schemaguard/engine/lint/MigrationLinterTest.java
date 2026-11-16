package io.github.hanumanthraghavendra.schemaguard.engine.lint;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Severity;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserOptions;
import io.github.hanumanthraghavendra.schemaguard.engine.planner.MigrationPlan;
import io.github.hanumanthraghavendra.schemaguard.engine.planner.PlanOptions;
import io.github.hanumanthraghavendra.schemaguard.engine.planner.Planner;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleRegistry;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RulesTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The linter on scripts written by hand, and on the planner's own scripts.
 *
 * <p>The second half ties three parts of the engine together. Run on the direct plan for a
 * pair of schemas, the linter must find what the risk rules find in the diff of that pair.
 * Run on the safe plan, it must find none of the risks the safe route claims to remove.
 */
class MigrationLinterTest {

    private static final String SCHEMA =
            """
            CREATE TABLE users (id int PRIMARY KEY, email text, name text);
            CREATE TABLE posts (id int PRIMARY KEY, user_id int, title text, views int);
            CREATE INDEX posts_title_idx ON posts (title);
            CREATE TYPE mood AS ENUM ('sad', 'ok');
            """;

    private static LintReport lint(String script) {
        return MigrationLinter.lint(RulesTest.catalog(SCHEMA), script);
    }

    private static List<String> rules(LintReport report) {
        return report.findings().stream().map(finding -> finding.rule().id()).toList();
    }

    // ---------------------------------------------------------------- the statement decides

    @Test
    void aPlainIndexBuildIsReportedAndAConcurrentOneIsNot() {
        LintReport plain = lint("SET lock_timeout = '5s'; CREATE INDEX posts_user_idx ON posts (user_id);");
        assertThat(rules(plain)).containsExactly("SG-R101");
        assertThat(plain.statements().get(1).operations()).containsExactly(Operation.CREATE_INDEX);
        assertThat(plain.statements().get(1).table()).contains(new QualifiedName("public", "posts"));

        LintReport concurrent = lint("CREATE INDEX CONCURRENTLY posts_user_idx ON posts (user_id);");
        assertThat(concurrent.isClean()).isTrue();
        assertThat(concurrent.score().value()).isZero();
        assertThat(concurrent.statements().get(0).operations()).containsExactly(Operation.CREATE_INDEX_CONCURRENTLY);
        assertThat(concurrent.result().indexes()).containsKey(new QualifiedName("public", "posts_user_idx"));
    }

    @Test
    void theSafeWayToAForeignKeyIsQuietAndTheDirectWayIsNot() {
        String timeout = "SET lock_timeout = '5s';";
        assertThat(rules(lint(timeout + "ALTER TABLE posts ADD CONSTRAINT posts_user_fk FOREIGN KEY (user_id) REFERENCES users (id);")))
                .containsExactly("SG-R104");
        LintReport safe =
                lint(
                        timeout
                                + "ALTER TABLE posts ADD CONSTRAINT posts_user_fk FOREIGN KEY (user_id) REFERENCES users (id) NOT VALID;"
                                + "ALTER TABLE posts VALIDATE CONSTRAINT posts_user_fk;");
        assertThat(safe.isClean()).isTrue();
        assertThat(safe.statements().get(1).operations()).containsExactly(Operation.ADD_FOREIGN_KEY_NOT_VALID);
        assertThat(safe.statements().get(2).operations()).containsExactly(Operation.VALIDATE_FOREIGN_KEY);
    }

    @Test
    void notNullThroughAValidatedCheckIsQuiet() {
        LintReport report =
                lint(
                        """
                        SET lock_timeout = '5s';
                        ALTER TABLE posts ADD CONSTRAINT posts_title_present CHECK (title IS NOT NULL) NOT VALID;
                        ALTER TABLE posts VALIDATE CONSTRAINT posts_title_present;
                        ALTER TABLE posts ALTER COLUMN title SET NOT NULL;
                        ALTER TABLE posts DROP CONSTRAINT posts_title_present;
                        """);
        assertThat(report.statements().get(3).operations()).containsExactly(Operation.SET_NOT_NULL_WITH_VALID_CHECK);
        assertThat(report.statements().get(3).findings()).isEmpty();
        // The helper constraint is added and dropped by the script itself, so its drop takes nothing away.
        assertThat(report.isClean()).isTrue();
        assertThat(rules(lint("SET lock_timeout = '5s'; ALTER TABLE users DROP CONSTRAINT users_pkey;"))).containsExactly("SG-R503");
        assertThat(rules(lint("SET lock_timeout = '5s'; ALTER TABLE posts ALTER COLUMN title SET NOT NULL;"))).containsExactly("SG-R106");
    }

    @Test
    void aKeyTakenOverFromAnIndexIsQuiet() {
        LintReport report =
                lint(
                        """
                        SET lock_timeout = '5s';
                        CREATE UNIQUE INDEX CONCURRENTLY users_email_key ON users (email);
                        ALTER TABLE users ADD CONSTRAINT users_email_key UNIQUE USING INDEX users_email_key;
                        """);
        assertThat(report.isClean()).isTrue();
        assertThat(report.statements().get(2).operations()).containsExactly(Operation.ADD_CONSTRAINT_USING_INDEX);
        assertThat(rules(lint("SET lock_timeout = '5s'; ALTER TABLE users ADD CONSTRAINT users_email_key UNIQUE (email);")))
                .containsExactly("SG-R108");
    }

    @Test
    void droppingAColumnReportsTheColumnAndNotTheIndexThatGoesWithIt() {
        LintReport report = lint("SET lock_timeout = '5s'; ALTER TABLE posts DROP COLUMN title;");
        assertThat(rules(report)).containsExactly("SG-R302");
        assertThat(report.statements().get(1).operations()).containsExactly(Operation.DROP_COLUMN);
        assertThat(report.result().indexes()).isEmpty();
        assertThat(report.level()).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    void aTableTheScriptCreatesCanBeChangedFreely() {
        LintReport report =
                lint(
                        """
                        CREATE TABLE comments (id int, post_id int REFERENCES posts (id), body text);
                        CREATE INDEX comments_post_idx ON comments (post_id);
                        ALTER TABLE comments ADD CONSTRAINT comments_pk PRIMARY KEY (id);
                        ALTER TABLE comments ALTER COLUMN body SET NOT NULL, ALTER COLUMN id TYPE bigint;
                        ALTER TABLE comments RENAME TO remarks;
                        ALTER TABLE remarks DROP COLUMN body;
                        ALTER TABLE remarks SET SCHEMA archive;
                        CREATE INDEX remarks_id_idx ON archive.remarks (id);
                        DROP TABLE archive.remarks;
                        """);
        assertThat(report.diagnostics()).isEmpty();
        assertThat(report.findings()).isEmpty();
        assertThat(report.statements().get(0).operations()).containsExactly(Operation.CREATE_TABLE_WITH_FOREIGN_KEY);
        assertThat(report.statements().get(3).operations()).containsExactly(Operation.SET_NOT_NULL, Operation.ALTER_TYPE_REWRITE);
    }

    @Test
    void eachActionOfOneAlterTableIsJudgedOnItsOwn() {
        LintReport report =
                lint("SET lock_timeout = '5s'; ALTER TABLE posts ADD COLUMN slug text, ALTER COLUMN views TYPE bigint, DROP COLUMN title;");
        assertThat(report.statements().get(1).operations())
                .containsExactly(Operation.ADD_COLUMN, Operation.ALTER_TYPE_REWRITE, Operation.DROP_COLUMN);
        assertThat(rules(report)).containsExactly("SG-R302", "SG-R201");
    }

    @Test
    void aNewColumnWithAForeignKeyChecksNoRows() {
        LintReport report = lint("SET lock_timeout = '5s'; ALTER TABLE posts ADD COLUMN editor_id int REFERENCES users (id);");
        assertThat(report.statements().get(1).operations()).containsExactly(Operation.ADD_COLUMN_WITH_FOREIGN_KEY, Operation.ADD_COLUMN);
        assertThat(report.isClean()).isTrue();
        assertThat(rules(lint("SET lock_timeout = '5s'; ALTER TABLE posts ADD COLUMN code text UNIQUE;"))).containsExactly("SG-R108");
    }

    // ---------------------------------------------------------------- what only a script shows

    @Test
    void aRenameIsReportedAsARenameAndNotAsADrop() {
        LintReport report =
                lint(
                        """
                        SET lock_timeout = '5s';
                        ALTER TABLE users RENAME COLUMN name TO full_name;
                        ALTER TABLE posts RENAME TO articles;
                        ALTER TABLE articles SET SCHEMA blog;
                        ALTER TYPE mood RENAME VALUE 'sad' TO 'unhappy';
                        ALTER TABLE users RENAME CONSTRAINT users_pkey TO users_primary;
                        ALTER INDEX blog.posts_title_idx RENAME TO articles_title_idx;
                        """);
        assertThat(report.diagnostics()).isEmpty();
        assertThat(rules(report)).containsExactly("SG-R510", "SG-R510", "SG-R510", "SG-R510");
        assertThat(report.findings().get(0).message())
                .isEqualTo("Column public.users.name becomes full_name. Queries that still use the old name fail at once.");
        assertThat(report.findings().get(0).operation()).contains(Operation.RENAME_COLUMN);
        assertThat(report.findings().get(2).message()).startsWith("Table public.articles moves to schema blog.");
        assertThat(report.statements().get(5).operations()).containsExactly(Operation.RENAME_CONSTRAINT);
        assertThat(report.statements().get(6).operations()).containsExactly(Operation.RENAME_INDEX);
        assertThat(report.result().table(new QualifiedName("blog", "articles"))).isPresent();
        assertThat(report.level()).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    void concurrentlyInsideATransactionBlockIsReported() {
        LintReport report =
                lint(
                        """
                        BEGIN;
                        CREATE INDEX CONCURRENTLY posts_user_idx ON posts (user_id);
                        DROP INDEX CONCURRENTLY posts_title_idx;
                        COMMIT;
                        CREATE INDEX CONCURRENTLY posts_views_idx ON posts (views);
                        START TRANSACTION;
                        CREATE INDEX CONCURRENTLY users_name_idx ON users (name);
                        ROLLBACK;
                        """);
        assertThat(rules(report)).containsExactly("SG-R403", "SG-R403", "SG-R403");
        assertThat(report.statements().get(4).findings()).isEmpty();
        assertThat(report.count(StatementLint.Status.NOT_SCHEMA)).isEqualTo(4);
        assertThat(report.count(StatementLint.Status.ANALYSED)).isEqualTo(4);
    }

    @Test
    void aMissingLockTimeoutIsReportedOnceOnTheFirstStatementThatNeedsIt() {
        LintReport report =
                lint(
                        """
                        CREATE INDEX CONCURRENTLY posts_user_idx ON posts (user_id);
                        ALTER TABLE posts ADD COLUMN slug text;
                        ALTER TABLE users ADD COLUMN bio text;
                        SET LOCAL lock_timeout = '2s';
                        ALTER TABLE users ADD COLUMN age int;
                        """);
        assertThat(rules(report)).containsExactly("SG-R111");
        assertThat(report.statements().get(1).findings()).hasSize(1);
        assertThat(report.findings().get(0).message())
                .startsWith("No lock_timeout is set before this statement. The script locks public.posts, public.users against");
        assertThat(report.findings().get(0).severity()).isEqualTo(RiskLevel.LOW);
        assertThat(lint("set lock_timeout to 3000; ALTER TABLE posts ADD COLUMN slug text;").isClean()).isTrue();
        assertThat(rules(lint("SET statement_timeout = '5s'; ALTER TABLE posts ADD COLUMN slug text;"))).containsExactly("SG-R111");
    }

    @Test
    void aRuleThatIsSwitchedOffStaysOff() {
        RuleRegistry fewer = RuleRegistry.standard().without(Set.of("lock-timeout", "SG-R302"));
        LintReport report =
                MigrationLinter.lint(RulesTest.catalog(SCHEMA), "ALTER TABLE posts DROP COLUMN title;", ParserOptions.defaults(), fewer);
        assertThat(report.isClean()).isTrue();
    }

    @Test
    void aGapTheScriptClosesItselfIsNotReported() {
        Catalog schema = RulesTest.catalog("CREATE TABLE t (id serial, code int NOT NULL DEFAULT 0);");
        LintReport closed =
                MigrationLinter.lint(
                        schema,
                        "SET lock_timeout = '5s';"
                                + " ALTER TABLE t ALTER COLUMN id DROP DEFAULT;"
                                + " ALTER TABLE t ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;");
        assertThat(rules(closed)).containsExactly("SG-R505");
        LintReport open = MigrationLinter.lint(schema, "SET lock_timeout = '5s'; ALTER TABLE t ALTER COLUMN code DROP DEFAULT;");
        assertThat(rules(open)).containsExactly("SG-R501");
    }

    // ---------------------------------------------------------------- what the linter cannot judge

    @Test
    void statementsThatWouldFailOrCannotBeReadAreSaidSo() {
        LintReport report =
                lint(
                        """
                        SET lock_timeout = '5s';
                        ALTER TABLE missing ADD COLUMN a int;
                        ALTER TABLE posts ADD COLUMN slug text, DROP COLUMN nope;
                        CREATE FUNCTION f() RETURNS int LANGUAGE sql AS 'SELECT 1';
                        ALTER TABLE posts ENABLE ROW LEVEL SECURITY;
                        ALTER TABLE posts ADD COLUMN;
                        UPDATE posts SET views = 0 WHERE views IS NULL;
                        ALTER TABLE posts ADD COLUMN ok int;
                        """);
        assertThat(report.statements()).extracting(StatementLint::status)
                .containsExactly(
                        StatementLint.Status.NOT_SCHEMA,
                        StatementLint.Status.WOULD_FAIL,
                        StatementLint.Status.WOULD_FAIL,
                        StatementLint.Status.NOT_ANALYSED,
                        StatementLint.Status.NOT_ANALYSED,
                        StatementLint.Status.NOT_ANALYSED,
                        StatementLint.Status.NOT_SCHEMA,
                        StatementLint.Status.ANALYSED);
        assertThat(report.diagnostics()).extracting(d -> d.span().line() + " " + d.message())
                .containsExactlyInAnyOrder(
                        "2 relation \"missing\" does not exist",
                        "3 column \"nope\" of relation \"posts\" does not exist",
                        "4 Unsupported statement: CREATE FUNCTION. It was not analysed.",
                        "5 Unsupported feature: ALTER TABLE ... ENABLE ROW",
                        "6 Expected a column name but found end of statement");
        assertThat(report.diagnostics()).filteredOn(d -> d.severity() == Severity.ERROR).hasSize(3);
        // The action that could be applied was applied; the script goes on after a failure.
        Catalog result = report.result();
        assertThat(result.table(new QualifiedName("public", "posts")).orElseThrow().column("slug")).isPresent();
        assertThat(result.table(new QualifiedName("public", "posts")).orElseThrow().column("ok")).isPresent();
        assertThat(report.isClean()).isTrue();
    }

    @Test
    void anEmptyScriptIsClean() {
        LintReport report = lint("  -- nothing to do\n");
        assertThat(report.statements()).isEmpty();
        assertThat(report.isClean()).isTrue();
        assertThat(report.level()).isEqualTo(RiskLevel.NONE);
        assertThat(LintFormatter.format(report)).isEqualTo("Risk: 0/100 NONE\nFindings: 0\n\n0 statements without findings\n");
    }

    // ---------------------------------------------------------------- report text

    @Test
    void theTextReportNamesTheLineTheLockAndTheSaferWay() {
        LintReport report =
                lint(
                        """
                        SET lock_timeout = '5s';

                        ALTER TABLE posts
                            ALTER COLUMN views TYPE bigint;
                        CREATE INDEX CONCURRENTLY posts_user_idx ON posts (user_id);
                        CREATE VIEW v AS SELECT 1;
                        ALTER TABLE nope ADD COLUMN a int;
                        """);
        assertThat(LintFormatter.format(report))
                .isEqualTo(
                        """
                        Risk: 60/100 HIGH
                        Findings: 1 (1 high)

                        line 3: ALTER TABLE posts
                          HIGH     SG-R201  Column type change rewrites the table  [table rewrite, score 60]
                              Changing public.posts.views from integer to bigint rewrites the whole table while reads and writes wait.
                              Lock: ALTER COLUMN ... TYPE takes ACCESS EXCLUSIVE on public.posts (blocks reads and writes), held while the whole table is written again
                              Safer: Add a new column of the new type, fill it in batches, keep it in step with a trigger, then switch the application over and drop the old column. Some changes need no rewrite at all: a longer varchar, varchar to text, or a numeric with more digits and the same scale.

                        1 statement without findings
                        line 6 not analysed: CREATE VIEW v AS SELECT 1
                        line 7 would fail: ALTER TABLE nope ADD COLUMN a int
                          warning at line 6: Unsupported statement: CREATE VIEW. It was not analysed.
                          error at line 7: relation "nope" does not exist
                        """);
    }

    @Test
    void longStatementsAreCutInTheReport() {
        assertThat(LintFormatter.firstLine("CREATE TABLE t (" + "a".repeat(200) + " int)")).hasSize(100).endsWith("...");
        assertThat(LintFormatter.firstLine("\n  DROP TABLE t\n")).isEqualTo("DROP TABLE t");
    }

    @Test
    void scriptRulesAreWellFormedAndDoNotClashWithTheStandardOnes() {
        assertThat(ScriptRules.ALL).extracting(RuleInfo::id).isSorted().doesNotHaveDuplicates();
        for (RuleInfo info : ScriptRules.ALL) {
            assertThat(RuleRegistry.standard().rule(info.id())).as(info.id()).isEmpty();
            assertThat(RuleRegistry.standard().rule(info.name())).as(info.name()).isEmpty();
            assertThat(info.id().charAt(4) - '1').as(info.id()).isEqualTo(info.category().ordinal());
        }
    }

    // ---------------------------------------------------------------- golden file

    @Test
    void theEvolutionMigrationMatchesTheReviewedGoldenFile() throws IOException {
        Catalog before = RulesTest.catalog(fixture("before.sql"));
        LintReport report = MigrationLinter.lint(before, fixture("migration.sql"));
        String actual = LintFormatter.format(report);
        Path golden = Path.of("src/test/resources/golden/evolution/expected-lint.txt");
        if (Boolean.getBoolean("golden.update")) {
            Files.writeString(golden, actual, StandardCharsets.UTF_8);
        }
        assertThat(actual).isEqualTo(Files.readString(golden, StandardCharsets.UTF_8));
        // The linter's idea of the schema after the script is the one PostgreSQL dumped.
        assertThat(io.github.hanumanthraghavendra.schemaguard.engine.diff.SchemaDiff.compare(
                                report.result(), RulesTest.catalog(fixture("after_pg_dump_16.sql")))
                        .significant())
                .isEmpty();
    }

    private static String fixture(String file) throws IOException {
        try (InputStream in = MigrationLinterTest.class.getResourceAsStream("/golden/evolution/" + file)) {
            assertThat(in).as("test resource %s", file).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------------- linter, rules and planner agree

    /** Rules whose finding in a diff has to show up again when the direct plan for that diff is linted. */
    private static final Set<String> SAME_IN_DIFF_AND_SCRIPT =
            Set.of(
                    "SG-R101", "SG-R102", "SG-R104", "SG-R105", "SG-R106", "SG-R107", "SG-R108", "SG-R110", "SG-R111",
                    "SG-R201", "SG-R202", "SG-R203", "SG-R204", "SG-R205", "SG-R207",
                    "SG-R301", "SG-R302", "SG-R304", "SG-R401", "SG-R402",
                    "SG-R501", "SG-R502", "SG-R503", "SG-R504", "SG-R505", "SG-R506", "SG-R507", "SG-R508", "SG-R509");

    static Stream<RulesTest.Case> ruleCases() {
        return RulesTest.cases().filter(example -> SAME_IN_DIFF_AND_SCRIPT.contains(example.rule()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ruleCases")
    void lintingTheDirectPlanFindsWhatTheRulesFindInTheDiff(RulesTest.Case example) {
        Catalog before = RulesTest.catalog(example.before());
        MigrationPlan plan = Planner.plan(before, RulesTest.catalog(example.after()));
        LintReport report = MigrationLinter.lint(before, plan.toSql());
        assertThat(report.diagnostics()).as("plan:\n%s", plan.toSql()).isEmpty();
        if (example.fires()) {
            assertThat(rules(report)).as("plan:\n%s", plan.toSql()).contains(example.rule());
        } else {
            assertThat(rules(report)).as("plan:\n%s", plan.toSql()).doesNotContain(example.rule());
        }
    }

    /** What the safe route promises to take away. */
    private static final Set<String> REMOVED_BY_THE_SAFE_ROUTE =
            Set.of("SG-R101", "SG-R102", "SG-R103", "SG-R104", "SG-R105", "SG-R106", "SG-R107", "SG-R108", "SG-R109", "SG-R111", "SG-R403");

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.github.hanumanthraghavendra.schemaguard.engine.rules.RulesTest#cases")
    void lintingTheSafePlanFindsNoneOfTheRisksTheSafeRouteRemoves(RulesTest.Case example) {
        Catalog before = RulesTest.catalog(example.before());
        MigrationPlan plan = Planner.plan(before, RulesTest.catalog(example.after()), PlanOptions.safe());
        LintReport report = MigrationLinter.lint(before, plan.toSql());
        assertThat(report.diagnostics()).as("plan:\n%s", plan.toSql()).isEmpty();
        assertThat(rules(report)).as("plan:\n%s", plan.toSql()).doesNotContainAnyElementsOf(REMOVED_BY_THE_SAFE_ROUTE);
    }
}
