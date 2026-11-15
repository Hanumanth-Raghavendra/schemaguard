package io.github.hanumanthraghavendra.schemaguard.engine.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogBuilder;
import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogResult;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.SchemaDiff;
import io.github.hanumanthraghavendra.schemaguard.engine.lint.ScriptRules;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import io.github.hanumanthraghavendra.schemaguard.engine.model.EnumType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Scores, the registry, and the two golden files of the risk engine.
 *
 * <p>{@code golden/evolution/expected-report.txt} is the reviewed report for the evolution
 * fixture, and {@code docs/RULES.md} is the rule reference. After an intended change, rewrite
 * both by running the build with {@code -Dgolden.update=true} and review the diff.
 */
class RiskReportTest {

    private static final Path GOLDEN_REPORT = Path.of("src/test/resources/golden/evolution/expected-report.txt");
    private static final Path RULE_REFERENCE = Path.of("../docs/RULES.md");

    private static final RuleInfo LOW = info("SG-R901", "low-rule", RiskLevel.LOW);
    private static final RuleInfo MEDIUM = info("SG-R902", "medium-rule", RiskLevel.MEDIUM);
    private static final RuleInfo HIGH = info("SG-R903", "high-rule", RiskLevel.HIGH);
    private static final RuleInfo CRITICAL = info("SG-R904", "critical-rule", RiskLevel.CRITICAL);
    private static final Change CHANGE = new Change.EnumAdded(new EnumType(new QualifiedName("public", "mood"), List.of("ok")));

    private static RuleInfo info(String id, String name, RiskLevel severity) {
        return new RuleInfo(id, name, Category.LOCKING, severity, "Title of " + name, "Why.", "Fix.");
    }

    private static Finding finding(RuleInfo rule) {
        return new Finding(rule, CHANGE, Optional.empty(), "Message.", Optional.empty(), 1.0);
    }

    private static Catalog fixture(String file) throws IOException {
        try (InputStream in = RiskReportTest.class.getResourceAsStream("/golden/evolution/" + file)) {
            assertThat(in).as("test resource %s", file).isNotNull();
            CatalogResult result = CatalogBuilder.fromSql(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            assertThat(result.hasErrors()).isFalse();
            return result.catalog();
        }
    }

    // ---------------------------------------------------------------- score

    @Test
    void oneFindingAloneScoresItsOwnWeightAndLandsInItsOwnBand() {
        for (RuleInfo rule : List.of(LOW, MEDIUM, HIGH, CRITICAL)) {
            RiskScore score = RiskScore.of(List.of(finding(rule)));
            assertThat(score.value()).isEqualTo(rule.severity().weight());
            assertThat(score.level()).isEqualTo(rule.severity());
        }
    }

    @Test
    void findingsCombineLikeIndependentChancesOfTrouble() {
        // 1 - (1 - 0.30) x (1 - 0.30) = 0.51
        assertThat(RiskScore.of(List.of(finding(MEDIUM), finding(MEDIUM))).value()).isEqualTo(51);
        assertThat(RiskScore.of(List.of(finding(MEDIUM), finding(MEDIUM))).level()).isEqualTo(RiskLevel.HIGH);
        // 1 - 0.9 x 0.9 x 0.9 = 0.271
        assertThat(RiskScore.of(List.of(finding(LOW), finding(LOW), finding(LOW))).value()).isEqualTo(27);
        // 1 - 0.40 x 0.15 = 0.94
        assertThat(RiskScore.of(List.of(finding(HIGH), finding(CRITICAL))).value()).isEqualTo(94);
    }

    @Test
    void everyFurtherFindingRaisesTheScoreButNeverPastOneHundred() {
        List<Finding> findings = new ArrayList<>();
        int previous = 0;
        for (int i = 0; i < 12; i++) {
            findings.add(finding(MEDIUM));
            int value = RiskScore.of(findings).value();
            assertThat(value).isGreaterThanOrEqualTo(previous).isLessThanOrEqualTo(100);
            previous = value;
        }
        assertThat(RiskScore.of(Collections.nCopies(50, finding(CRITICAL))).value()).isEqualTo(100);
    }

    @Test
    void theScoreDoesNotDependOnTheOrderOfTheFindings() {
        List<Finding> findings = List.of(finding(LOW), finding(CRITICAL), finding(MEDIUM), finding(HIGH));
        List<Finding> reversed = new ArrayList<>(findings);
        Collections.reverse(reversed);
        assertThat(RiskScore.of(reversed)).isEqualTo(RiskScore.of(findings));
    }

    @Test
    void theReasonsAreTheStrongestFindingsStrongestFirst() {
        List<Finding> findings = new ArrayList<>(Collections.nCopies(6, finding(LOW)));
        findings.add(finding(CRITICAL));
        RiskScore score = RiskScore.of(findings);
        assertThat(score.reasons()).hasSize(RiskScore.MAX_REASONS);
        assertThat(score.reasons().get(0)).isEqualTo("85 SG-R904 Title of critical-rule");
        assertThat(score.reasons().get(1)).isEqualTo("10 SG-R901 Title of low-rule");
    }

    @Test
    void noFindingsMeansNoRisk() {
        assertThat(RiskScore.of(List.of())).isEqualTo(new RiskScore(0, RiskLevel.NONE, List.of()));
    }

    @ParameterizedTest
    @CsvSource({"0, NONE", "1, LOW", "24, LOW", "25, MEDIUM", "49, MEDIUM", "50, HIGH", "74, HIGH", "75, CRITICAL", "100, CRITICAL"})
    void scoresFallIntoBands(int score, RiskLevel level) {
        assertThat(RiskLevel.ofScore(score)).isEqualTo(level);
    }

    @Test
    void certaintyScalesTheScoreOfAFindingButNeverToZero() {
        assertThat(finding(HIGH).withCertainty(0.5).score()).isEqualTo(30);
        assertThat(finding(LOW).withCertainty(0.01).score()).isEqualTo(1);
        assertThat(finding(HIGH).withOperation(Operation.DROP_TABLE).operation()).contains(Operation.DROP_TABLE);
        assertThatIllegalArgumentException().isThrownBy(() -> finding(HIGH).withCertainty(0));
        assertThatIllegalArgumentException().isThrownBy(() -> finding(HIGH).withCertainty(1.5));
        assertThatIllegalArgumentException().isThrownBy(() -> finding(HIGH).withCertainty(Double.NaN));
    }

    // ---------------------------------------------------------------- rule metadata and registry

    @Test
    void ruleMetadataIsValidated() {
        assertThatIllegalArgumentException().isThrownBy(() -> info("R1", "name", RiskLevel.LOW)).withMessageContaining("SG-R101");
        assertThatIllegalArgumentException().isThrownBy(() -> info("SG-R101", "Not Kebab", RiskLevel.LOW));
        assertThatIllegalArgumentException().isThrownBy(() -> info("SG-R101", "name", RiskLevel.NONE));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RuleInfo("SG-R101", "name", Category.LOCKING, RiskLevel.LOW, "Title", " ", "Fix"));
    }

    @Test
    void theStandardRulesAreOrderedAndTheirIdsMatchTheirCategory() {
        List<Rule> rules = RuleRegistry.standard().rules();
        assertThat(rules).hasSize(38);
        assertThat(rules).extracting(rule -> rule.info().id()).isSorted().doesNotHaveDuplicates();
        assertThat(rules).extracting(rule -> rule.info().name()).doesNotHaveDuplicates();
        for (Rule rule : rules) {
            RuleInfo info = rule.info();
            // SG-R1xx locking, 2xx rewrite, 3xx data loss, 4xx can fail, 5xx compatibility
            assertThat(info.id().charAt(4) - '1').as(info.id()).isEqualTo(info.category().ordinal());
            assertThat(info.title()).as(info.id()).doesNotEndWith(".");
            assertThat(info.why()).as(info.id()).endsWith(".");
            assertThat(info.fix()).as(info.id()).endsWith(".");
            assertThat(rule).hasToString(info.id() + " " + info.name());
        }
    }

    @Test
    void rulesCanBeFoundAndSwitchedOffByIdOrName() {
        RuleRegistry standard = RuleRegistry.standard();
        assertThat(standard.rule("SG-R301")).isPresent();
        assertThat(standard.rule("table-dropped")).isEqualTo(standard.rule("SG-R301"));
        assertThat(standard.rule("SG-R999")).isEmpty();

        RuleRegistry fewer = standard.without(Set.of("SG-R301", "lock-timeout"));
        assertThat(fewer.rules()).hasSize(36);
        assertThat(fewer.rule("SG-R301")).isEmpty();
        assertThat(fewer.rule("SG-R111")).isEmpty();
        assertThat(standard.rules()).hasSize(38);

        SchemaDiff diff = SchemaDiff.compare(RulesTest.catalog("CREATE TABLE t (a int);"), RulesTest.catalog(""));
        assertThat(RiskAnalyzer.analyze(diff).findings()).extracting(f -> f.rule().id()).containsExactly("SG-R301", "SG-R111");
        assertThat(RiskAnalyzer.analyze(diff, fewer).isClean()).isTrue();
    }

    @Test
    void aMisspelledRuleCannotBeSwitchedOffSilently() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RuleRegistry.standard().without(Set.of("table-droped")))
                .withMessage("Unknown rule: table-droped");
    }

    @Test
    void aRegistryRefusesTwoRulesWithOneId() {
        Rule rule = RuleRegistry.standard().rules().get(0);
        assertThatIllegalArgumentException().isThrownBy(() -> RuleRegistry.of(List.of(rule, rule)));
        assertThat(RuleRegistry.of(List.of(rule)).rules()).containsExactly(rule);
    }

    @Test
    void everyCategoryExplainsItself() {
        for (Category category : Category.values()) {
            assertThat(category.label()).isNotBlank();
            assertThat(category.description()).endsWith(".");
        }
    }

    // ---------------------------------------------------------------- text report

    @Test
    void theTextReportShowsTheScoreTheChangeAndTheSaferWay() {
        RiskReport report = RulesTest.report("CREATE TABLE t (a int, b int);", "CREATE TABLE t (a int, b int); CREATE INDEX t_a_idx ON t (a);");
        assertThat(ReportFormatter.format(report))
                .isEqualTo(
                        """
                        Risk: 60/100 HIGH
                        Findings: 1 (1 high)

                        + index public.t_a_idx ON public.t USING btree (a)
                          HIGH     SG-R101  Index is built without CONCURRENTLY  [locking, score 60]
                              Building index t_a_idx blocks every write to public.t until the build ends.
                              Direct route: CREATE INDEX takes SHARE on public.t (blocks writes, reads continue), held while every row is read
                              Safer: Use CREATE INDEX CONCURRENTLY. It builds the index without blocking writes. It cannot run inside a transaction, and if it fails it leaves an invalid index that has to be dropped before trying again.
                        """);
    }

    @Test
    void aCleanReportSaysSoInTwoLines() {
        RiskReport report = RulesTest.report("", "CREATE TABLE t (a int);");
        assertThat(ReportFormatter.format(report)).isEqualTo("Risk: 0/100 NONE\nFindings: 0\n\n1 change without findings\n");
        assertThat(ReportFormatter.format(RulesTest.report("", ""))).isEqualTo("Risk: 0/100 NONE\nFindings: 0\n");
    }

    @Test
    void theEffectLineNamesBothTablesOfAForeignKeyAndIsLeftOutWithoutALock() {
        Optional<QualifiedName> table = Optional.of(new QualifiedName("app", "posts"));
        assertThat(ReportFormatter.effect(Operation.ADD_FOREIGN_KEY, table))
                .contains(
                        "ADD CONSTRAINT ... FOREIGN KEY takes SHARE ROW EXCLUSIVE on app.posts (blocks writes, reads continue)"
                                + " and SHARE ROW EXCLUSIVE on each table it references (blocks writes, reads continue),"
                                + " held while every row is read");
        assertThat(ReportFormatter.effect(Operation.ALTER_TYPE_REWRITE, Optional.empty()))
                .contains(
                        "ALTER COLUMN ... TYPE takes ACCESS EXCLUSIVE on the table (blocks reads and writes),"
                                + " held while the whole table is written again");
        assertThat(ReportFormatter.effect(Operation.DROP_COLUMN, table).orElseThrow()).endsWith("held briefly");
        assertThat(ReportFormatter.effect(Operation.ADD_ENUM_VALUE, Optional.empty())).isEmpty();
    }

    // ---------------------------------------------------------------- golden files

    @Test
    void theEvolutionReportMatchesTheReviewedGoldenFile() throws IOException {
        SchemaDiff diff = SchemaDiff.compare(fixture("before_pg_dump_16.sql"), fixture("after_pg_dump_16.sql"));
        RiskReport report = RiskAnalyzer.analyze(diff);
        String actual = ReportFormatter.format(report);
        if (Boolean.getBoolean("golden.update")) {
            Files.writeString(GOLDEN_REPORT, actual, StandardCharsets.UTF_8);
        }
        assertThat(actual).isEqualTo(Files.readString(GOLDEN_REPORT, StandardCharsets.UTF_8));
        assertThat(report.level()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(report.findings()).allSatisfy(finding -> assertThat(diff.changes()).contains(finding.change()));
    }

    @Test
    void theRuleReferenceInTheDocsIsUpToDate() throws IOException {
        String actual = RuleReference.markdown(RuleRegistry.standard(), ScriptRules.ALL);
        assertThat(RuleReference.markdown(RuleRegistry.standard())).doesNotContain("script").contains("38 rules.");
        if (Boolean.getBoolean("golden.update")) {
            Files.writeString(RULE_REFERENCE, actual, StandardCharsets.UTF_8);
        }
        assertThat(actual).isEqualTo(Files.readString(RULE_REFERENCE, StandardCharsets.UTF_8));
    }
}
