package io.github.hanumanthraghavendra.schemaguard.engine.rules;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogBuilder;
import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogResult;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.SchemaDiff;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every rule on a schema change that must set it off and on one that must not.
 *
 * <p>The second kind matters as much as the first: a tool that warns about harmless changes
 * is a tool people stop reading.
 */
class RulesTest {

    /** One rule, an old schema, a new schema, and whether the rule has to report something. */
    record Case(String rule, boolean fires, String before, String after) {
        @Override
        public String toString() {
            return rule + (fires ? " fires: " : " is quiet: ") + after.strip().lines().reduce((a, b) -> b).orElse("(empty schema)");
        }
    }

    private static Case fires(String rule, String before, String after) {
        return new Case(rule, true, before, after);
    }

    private static Case quiet(String rule, String before, String after) {
        return new Case(rule, false, before, after);
    }

    static Catalog catalog(String sql) {
        CatalogResult result = CatalogBuilder.fromSql(sql);
        assertThat(result.diagnostics()).as("diagnostics for: %s", sql).isEmpty();
        return result.catalog();
    }

    static RiskReport report(String before, String after) {
        return RiskAnalyzer.analyze(SchemaDiff.compare(catalog(before), catalog(after)));
    }

    private static final String T = "CREATE TABLE t (a int, b int);";
    private static final String ENUM = "CREATE TYPE mood AS ENUM ('sad', 'ok');";

    static Stream<Case> cases() {
        return Stream.of(
                // ------------------------------------------------------------ locking
                fires("SG-R101", T, T + "CREATE INDEX t_a_idx ON t (a);"),
                fires("SG-R101", T, T + "CREATE UNIQUE INDEX t_a_idx ON t (a);"),
                quiet("SG-R101", "", T + "CREATE INDEX t_a_idx ON t (a);"),
                fires("SG-R102", T + "CREATE UNIQUE INDEX t_a_idx ON t (a);", T),
                quiet("SG-R102", T + "CREATE INDEX t_a_idx ON t (a);", ""),
                fires("SG-R103", T + "CREATE INDEX t_idx ON t (a);", T + "CREATE INDEX t_idx ON t (a, b);"),
                quiet("SG-R103", T + "CREATE INDEX t_idx ON t (a);", "CREATE TABLE t (a int, b int, c int); CREATE INDEX t_idx ON t (a);"),
                fires(
                        "SG-R104",
                        "CREATE TABLE p (id int PRIMARY KEY);" + T,
                        "CREATE TABLE p (id int PRIMARY KEY);" + T + "ALTER TABLE t ADD CONSTRAINT t_fk FOREIGN KEY (a) REFERENCES p (id);"),
                quiet(
                        "SG-R104",
                        "CREATE TABLE p (id int PRIMARY KEY);" + T,
                        "CREATE TABLE p (id int PRIMARY KEY);" + T + "ALTER TABLE t ADD CONSTRAINT t_fk FOREIGN KEY (a) REFERENCES p (id) NOT VALID;"),
                quiet(
                        "SG-R104",
                        "CREATE TABLE p (id int PRIMARY KEY);",
                        "CREATE TABLE p (id int PRIMARY KEY); CREATE TABLE t (a int REFERENCES p (id));"),
                fires("SG-R105", T, T + "ALTER TABLE t ADD CONSTRAINT positive CHECK (a > 0);"),
                quiet("SG-R105", T, T + "ALTER TABLE t ADD CONSTRAINT positive CHECK (a > 0) NOT VALID;"),
                quiet("SG-R105", "", "CREATE TABLE t (a int CHECK (a > 0));"),
                fires("SG-R106", T, "CREATE TABLE t (a int NOT NULL, b int);"),
                quiet(
                        "SG-R106",
                        "CREATE TABLE t (a int, b int, CONSTRAINT a_present CHECK (a IS NOT NULL));",
                        "CREATE TABLE t (a int NOT NULL, b int, CONSTRAINT a_present CHECK (a IS NOT NULL));"),
                fires(
                        "SG-R106",
                        T + "ALTER TABLE t ADD CONSTRAINT a_present CHECK (a IS NOT NULL) NOT VALID;",
                        "CREATE TABLE t (a int NOT NULL, b int);"),
                quiet("SG-R106", "CREATE TABLE t (a int NOT NULL, b int);", T),
                fires("SG-R107", T, T + "ALTER TABLE t ADD PRIMARY KEY (a);"),
                quiet("SG-R107", "", "CREATE TABLE t (a int PRIMARY KEY);"),
                fires("SG-R108", T, T + "ALTER TABLE t ADD CONSTRAINT t_a_key UNIQUE (a);"),
                quiet("SG-R108", "", "CREATE TABLE t (a int UNIQUE);"),
                fires(
                        "SG-R109",
                        "CREATE TABLE t (a int, CONSTRAINT limits CHECK (a > 0));",
                        "CREATE TABLE t (a int, CONSTRAINT limits CHECK (a > 1));"),
                fires(
                        "SG-R109",
                        "CREATE TABLE t (a int, CONSTRAINT one UNIQUE (a));",
                        "CREATE TABLE t (a int, CONSTRAINT one UNIQUE NULLS NOT DISTINCT (a));"),
                quiet(
                        "SG-R109",
                        "CREATE TABLE t (a int, CONSTRAINT limits CHECK (a > 0));",
                        "CREATE TABLE t (a int); ALTER TABLE t ADD CONSTRAINT limits CHECK (a > 1) NOT VALID;"),
                fires(
                        "SG-R110",
                        "CREATE TABLE t (c text); CREATE INDEX t_c_idx ON t (c);",
                        "CREATE TABLE t (c text COLLATE \"C\"); CREATE INDEX t_c_idx ON t (c);"),
                fires("SG-R110", "CREATE TABLE t (c text PRIMARY KEY);", "CREATE TABLE t (c text COLLATE \"C\" PRIMARY KEY);"),
                fires(
                        "SG-R110",
                        "CREATE TABLE t (c text); CREATE INDEX t_c_idx ON t (lower(c));",
                        "CREATE TABLE t (c text COLLATE \"C\"); CREATE INDEX t_c_idx ON t (lower(c));"),
                quiet("SG-R110", "CREATE TABLE t (c text, d text UNIQUE);", "CREATE TABLE t (c text COLLATE \"C\", d text UNIQUE);"),
                fires("SG-R111", T, "CREATE TABLE t (a int, b int, c int);"),
                quiet("SG-R111", "", T),
                quiet("SG-R111", T, T + "CREATE INDEX t_a_idx ON t (a);"),

                // ------------------------------------------------------------ rewrite
                fires("SG-R201", T, "CREATE TABLE t (a bigint, b int);"),
                quiet("SG-R201", "CREATE TABLE t (a varchar(10));", "CREATE TABLE t (a varchar(20));"),
                fires("SG-R202", T, "CREATE TABLE t (a int, b int, c uuid DEFAULT gen_random_uuid());"),
                fires("SG-R202", T, "CREATE TABLE t (a int, b int, c serial);"),
                quiet("SG-R202", T, "CREATE TABLE t (a int, b int, c timestamptz DEFAULT now());"),
                quiet("SG-R202", "", "CREATE TABLE t (c uuid DEFAULT gen_random_uuid());"),
                fires("SG-R203", T, "CREATE TABLE t (a int, b int, c text DEFAULT next_code());"),
                quiet("SG-R203", T, "CREATE TABLE t (a int, b int, c int DEFAULT 5);"),
                quiet("SG-R203", T, "CREATE TABLE t (a int, b int, c float8 DEFAULT random());"),
                fires("SG-R204", T, "CREATE TABLE t (a int, b int, c int GENERATED ALWAYS AS IDENTITY);"),
                quiet("SG-R204", "", "CREATE TABLE t (c int GENERATED ALWAYS AS IDENTITY);"),
                fires("SG-R205", T, "CREATE TABLE t (a int, b int, c int GENERATED ALWAYS AS (a * 2) STORED);"),
                quiet("SG-R205", "", "CREATE TABLE t (a int, c int GENERATED ALWAYS AS (a * 2) STORED);"),
                fires(
                        "SG-R206",
                        "CREATE TABLE t (a int, c int GENERATED ALWAYS AS (a * 2) STORED);",
                        "CREATE TABLE t (a int, c int GENERATED ALWAYS AS (a * 3) STORED);"),
                fires("SG-R206", "CREATE TABLE t (a int, c int);", "CREATE TABLE t (a int, c int GENERATED ALWAYS AS (a * 3) STORED);"),
                quiet("SG-R206", "CREATE TABLE t (a int, c int GENERATED ALWAYS AS (a * 2) STORED);", "CREATE TABLE t (a int, c int);"),
                fires("SG-R207", T, "CREATE UNLOGGED TABLE t (a int, b int);"),
                fires("SG-R207", "CREATE UNLOGGED TABLE t (a int, b int);", T),
                quiet("SG-R207", "", "CREATE UNLOGGED TABLE t (a int, b int);"),
                fires("SG-R208", T, "CREATE TABLE t (a int, b int) PARTITION BY RANGE (a);"),
                quiet(
                        "SG-R208",
                        "CREATE TABLE t (a int, b int) PARTITION BY RANGE (a);",
                        "CREATE TABLE t (a int, b int) PARTITION BY RANGE (a);"),
                fires("SG-R209", ENUM, "CREATE TYPE mood AS ENUM ('ok', 'sad');"),
                quiet("SG-R209", ENUM, "CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy');"),
                fires("SG-R210", T, "CREATE TABLE t (b int, a int);"),
                quiet("SG-R210", T, "CREATE TABLE t (a int, b int, c int);"),

                // ------------------------------------------------------------ data loss
                fires("SG-R301", T, ""),
                quiet("SG-R301", "", T),
                fires("SG-R302", T, "CREATE TABLE t (a int);"),
                quiet("SG-R302", T, ""),
                fires("SG-R303", ENUM, "CREATE TYPE mood AS ENUM ('sad');"),
                quiet("SG-R303", ENUM, "CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy');"),
                fires("SG-R304", "CREATE TABLE t (a varchar(20));", "CREATE TABLE t (a varchar(10));"),
                fires("SG-R304", "CREATE TABLE t (a bigint);", "CREATE TABLE t (a int);"),
                quiet("SG-R304", "CREATE TABLE t (a varchar(10));", "CREATE TABLE t (a varchar(20));"),
                quiet("SG-R304", "CREATE TABLE t (a text);", "CREATE TABLE t (a int);"),
                fires("SG-R305", "CREATE TABLE t (id int, display_name text);", "CREATE TABLE t (id int, full_name text);"),
                fires("SG-R305", "CREATE TABLE audit_log (id int, at timestamptz);", "CREATE TABLE activity_log (id int, at timestamptz);"),
                quiet("SG-R305", T, "CREATE TABLE t (a int, b int, c int);"),
                fires("SG-R306", "CREATE TYPE role AS ENUM ('reader', 'writer');", "CREATE TYPE role AS ENUM ('reader', 'author');"),
                quiet("SG-R306", ENUM, "CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy');"),

                // ------------------------------------------------------------ can fail
                fires("SG-R401", T, "CREATE TABLE t (a int, b int, c int NOT NULL);"),
                quiet("SG-R401", T, "CREATE TABLE t (a int, b int, c int NOT NULL DEFAULT 0);"),
                quiet("SG-R401", T, "CREATE TABLE t (a int, b int, c int GENERATED ALWAYS AS IDENTITY);"),
                quiet("SG-R401", "", "CREATE TABLE t (c int NOT NULL);"),
                fires("SG-R402", "CREATE TABLE t (a text);", "CREATE TABLE t (a int);"),
                fires("SG-R402", "CREATE TABLE t (a timestamp);", "CREATE TABLE t (a timestamptz);"),
                quiet("SG-R402", T, "CREATE TABLE t (a bigint, b int);"),

                // ------------------------------------------------------------ compatibility
                fires("SG-R501", "CREATE TABLE t (a int NOT NULL DEFAULT 0);", "CREATE TABLE t (a int NOT NULL);"),
                quiet("SG-R501", "CREATE TABLE t (a int DEFAULT 0);", "CREATE TABLE t (a int);"),
                quiet("SG-R501", "CREATE TABLE t (a int NOT NULL DEFAULT 0);", "CREATE TABLE t (a int NOT NULL DEFAULT 1);"),
                quiet("SG-R501", "CREATE TABLE t (a serial);", "CREATE TABLE t (a int GENERATED BY DEFAULT AS IDENTITY);"),
                fires("SG-R502", "CREATE TABLE t (a int NOT NULL);", "CREATE TABLE t (a int);"),
                quiet("SG-R502", "CREATE TABLE t (a int);", "CREATE TABLE t (a int NOT NULL);"),
                fires("SG-R503", "CREATE TABLE t (a int, CONSTRAINT positive CHECK (a > 0));", "CREATE TABLE t (a int);"),
                fires("SG-R503", "CREATE TABLE t (a int NOT NULL, b int, CONSTRAINT pk PRIMARY KEY (a));", "CREATE TABLE t (a int NOT NULL, b int);"),
                fires("SG-R503", "CREATE TABLE t (a int, CONSTRAINT one UNIQUE (a));", "CREATE TABLE t (a int);"),
                fires(
                        "SG-R503",
                        "CREATE TABLE p (id int PRIMARY KEY); CREATE TABLE t (a int REFERENCES p (id));",
                        "CREATE TABLE p (id int PRIMARY KEY); CREATE TABLE t (a int);"),
                quiet("SG-R503", "CREATE TABLE t (a int, b int, CONSTRAINT positive CHECK (b > 0));", "CREATE TABLE t (a int);"),
                quiet(
                        "SG-R503",
                        "CREATE TABLE p (id int PRIMARY KEY); CREATE TABLE t (a int REFERENCES p (id));",
                        "CREATE TABLE t (a int);"),
                fires("SG-R504", "CREATE SEQUENCE invoice_number;", ""),
                quiet("SG-R504", "CREATE TABLE t (id serial);", ""),
                quiet("SG-R504", "CREATE TABLE t (id serial, a int);", "CREATE TABLE t (a int);"),
                fires("SG-R505", "CREATE TABLE t (id int NOT NULL);", "CREATE TABLE t (id int GENERATED BY DEFAULT AS IDENTITY);"),
                fires("SG-R505", "CREATE TABLE t (id serial);", "CREATE TABLE t (id int GENERATED BY DEFAULT AS IDENTITY);"),
                quiet("SG-R505", "CREATE TABLE t (id int GENERATED BY DEFAULT AS IDENTITY);", "CREATE TABLE t (id int NOT NULL);"),
                fires("SG-R506", "CREATE TABLE t (id int GENERATED BY DEFAULT AS IDENTITY);", "CREATE TABLE t (id int NOT NULL);"),
                quiet("SG-R506", "CREATE TABLE t (id int GENERATED BY DEFAULT AS IDENTITY);", "CREATE TABLE t (id int NOT NULL DEFAULT 0);"),
                quiet("SG-R506", "CREATE TABLE t (id int NOT NULL);", "CREATE TABLE t (id int GENERATED ALWAYS AS IDENTITY);"),
                fires(
                        "SG-R507",
                        "CREATE TABLE t (id int GENERATED BY DEFAULT AS IDENTITY);",
                        "CREATE TABLE t (id int GENERATED ALWAYS AS IDENTITY);"),
                quiet(
                        "SG-R507",
                        "CREATE TABLE t (id int GENERATED ALWAYS AS IDENTITY);",
                        "CREATE TABLE t (id int GENERATED BY DEFAULT AS IDENTITY);"),
                fires("SG-R508", ENUM, "CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy');"),
                quiet("SG-R508", "", ENUM),
                fires("SG-R509", "CREATE TABLE p (a int); CREATE TABLE c (a int);", "CREATE TABLE p (a int); CREATE TABLE c (a int) INHERITS (p);"),
                quiet("SG-R509", "CREATE TABLE p (a int); CREATE TABLE c (a int) INHERITS (p);", "CREATE TABLE p (a int); CREATE TABLE c (a int) INHERITS (p);"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void eachRuleFiresExactlyWhereItShould(Case example) {
        List<Finding> findings =
                report(example.before(), example.after()).findings().stream()
                        .filter(finding -> finding.rule().id().equals(example.rule()))
                        .toList();
        if (example.fires()) {
            assertThat(findings).as("findings of %s", example.rule()).isNotEmpty();
        } else {
            assertThat(findings).as("findings of %s", example.rule()).isEmpty();
        }
    }

    @Test
    void everyStandardRuleHasACaseThatFiresAndOneThatDoesNot() {
        Set<String> all = RuleRegistry.standard().rules().stream().map(rule -> rule.info().id()).collect(Collectors.toSet());
        assertThat(cases().filter(Case::fires).map(Case::rule).collect(Collectors.toSet())).isEqualTo(all);
        assertThat(cases().filter(example -> !example.fires()).map(Case::rule).collect(Collectors.toSet())).isEqualTo(all);
    }

    @Test
    void aSchemaComparedWithItselfIsClean() {
        RiskReport report = report(T + ENUM, T + ENUM);
        assertThat(report.isClean()).isTrue();
        assertThat(report.score().value()).isZero();
        assertThat(report.level()).isEqualTo(RiskLevel.NONE);
        assertThat(report.score().reasons()).isEmpty();
    }

    @Test
    void aBrandNewSchemaIsClean() {
        String schema =
                """
                CREATE TYPE mood AS ENUM ('sad', 'ok');
                CREATE TABLE p (id int GENERATED ALWAYS AS IDENTITY PRIMARY KEY, code uuid DEFAULT gen_random_uuid());
                CREATE TABLE t (id serial PRIMARY KEY, p_id int NOT NULL REFERENCES p (id), m mood, CHECK (p_id > 0));
                CREATE INDEX t_p_idx ON t (p_id);
                """;
        assertThat(report("", schema).findings()).isEmpty();
    }

    @Test
    void cosmeticChangesProduceNoFindings() {
        RiskReport report =
                report(
                        "CREATE TABLE t (a int DEFAULT 0, CONSTRAINT positive CHECK (a > 0));",
                        "CREATE TABLE t (a int DEFAULT (0)::integer, CONSTRAINT positive CHECK ((a > (0)::integer)));");
        assertThat(report.diff().changes()).isNotEmpty().allMatch(change -> change.cosmetic());
        assertThat(report.findings()).isEmpty();
    }

    @Test
    void theSafeRouteToNotNullIsFullyQuiet() {
        // Step 3 of the safe route: the valid CHECK is already there, so SET NOT NULL does not scan.
        RiskReport report =
                report(
                        "CREATE TABLE t (a int, CONSTRAINT a_present CHECK (a IS NOT NULL));",
                        "CREATE TABLE t (a int NOT NULL, CONSTRAINT a_present CHECK (a IS NOT NULL));");
        assertThat(report.findings()).extracting(finding -> finding.rule().id()).containsExactly("SG-R111");
        assertThat(DirectRoute.of(report.diff().changes().get(0), new RuleContext(report.diff())))
                .contains(Operation.SET_NOT_NULL_WITH_VALID_CHECK);
    }

    @Test
    void validatingAConstraintIsNotReported() {
        RiskReport report =
                report(
                        T + "ALTER TABLE t ADD CONSTRAINT positive CHECK (a > 0) NOT VALID;",
                        T + "ALTER TABLE t ADD CONSTRAINT positive CHECK (a > 0);");
        assertThat(report.diff().changes()).hasSize(1);
        assertThat(report.findings()).isEmpty();
        assertThat(DirectRoute.of(report.diff().changes().get(0), new RuleContext(report.diff())))
                .contains(Operation.VALIDATE_CHECK);
    }

    @Test
    void theLockTimeoutAdviceComesOnceAndNamesEveryLockedTable() {
        RiskReport report =
                report(
                        "CREATE TABLE t (a int); CREATE TABLE u (a int); CREATE TABLE v (a int);",
                        "CREATE TABLE t (a int, b int); CREATE TABLE u (a int, b int); CREATE TABLE v (a int);");
        assertThat(report.findings())
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.rule().name()).isEqualTo("lock-timeout");
                            assertThat(finding.message()).contains("locks 2 tables public.t, public.u against");
                            assertThat(finding.operation()).isEmpty();
                        });
    }

    @Test
    void aPossibleRenameCountsInProportionToItsConfidence() {
        RiskReport report = report("CREATE TABLE t (id int, display_name text);", "CREATE TABLE t (id int, full_name text);");
        Finding rename = report.findings().stream().filter(f -> f.rule().id().equals("SG-R305")).findFirst().orElseThrow();
        assertThat(rename.certainty()).isEqualTo(0.65);
        assertThat(rename.score()).isEqualTo(39);
        assertThat(rename.severity()).isEqualTo(RiskLevel.HIGH);
        assertThat(rename.message()).isEqualTo(
                "Column public.t.display_name may have been renamed to full_name. Applied as drop and add, its values are lost.");
    }

    @Test
    void anUnknownDefaultCountsHalf() {
        RiskReport report = report(T, "CREATE TABLE t (a int, b int, c text DEFAULT next_code());");
        Finding finding = report.findings().stream().filter(f -> f.rule().id().equals("SG-R203")).findFirst().orElseThrow();
        assertThat(finding.certainty()).isEqualTo(0.5);
        assertThat(finding.score()).isEqualTo(15);
    }

    @Test
    void findingsCarryTheTableAndTheStatementOfTheDirectRoute() {
        RiskReport report = report(T, T + "CREATE INDEX t_a_idx ON t (a);");
        Finding finding = report.findings().get(0);
        assertThat(finding.table()).map(Object::toString).contains("public.t");
        assertThat(finding.operation()).contains(Operation.CREATE_INDEX);
        assertThat(finding.message()).isEqualTo("Building index t_a_idx blocks every write to public.t until the build ends.");
        assertThat(report.findingsFor(report.diff().changes().get(0))).containsExactly(finding);
        assertThat(report.count(RiskLevel.HIGH)).isEqualTo(1);
        assertThat(report.count(RiskLevel.CRITICAL)).isZero();
    }

    @Test
    void aTableThatMayBeARenamedOneCountsAsHoldingRows() {
        RiskReport report =
                report(
                        "CREATE TABLE audit_log (id int, at timestamptz);",
                        "CREATE TABLE activity_log (id int, at timestamptz); CREATE INDEX activity_at_idx ON activity_log (at);");
        assertThat(report.findings()).extracting(finding -> finding.rule().id()).contains("SG-R101", "SG-R301", "SG-R305");
    }

    @Test
    void findingsFollowTheOrderOfTheDiffAndThenSeverity() {
        RiskReport report = report("CREATE TABLE t (a varchar(20), b int);", "CREATE TABLE t (a varchar(10));");
        assertThat(report.findings())
                .extracting(finding -> finding.rule().id())
                .containsExactly("SG-R201", "SG-R304", "SG-R111", "SG-R302");
    }
}
