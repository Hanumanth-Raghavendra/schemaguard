package io.github.hanumanthraghavendra.schemaguard.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogBuilder;
import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogResult;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.SchemaDiff;
import io.github.hanumanthraghavendra.schemaguard.engine.lint.LintFormatter;
import io.github.hanumanthraghavendra.schemaguard.engine.lint.MigrationLinter;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.Parser;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserOptions;
import io.github.hanumanthraghavendra.schemaguard.engine.planner.PlanOptions;
import io.github.hanumanthraghavendra.schemaguard.engine.planner.Planner;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ReportFormatter;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskAnalyzer;
import io.github.hanumanthraghavendra.schemaguard.engine.sql.SqlWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The engine is going to sit behind a public API, so any input must give an answer, never an
 * exception. This test throws broken SQL at every stage: the golden fixtures, cut, spliced and
 * sprinkled with tokens that tend to confuse a parser. The seed is fixed, so a failure can be
 * reproduced; before release the same harness ran 60,000 cases with no failure.
 */
class EngineRobustnessTest {

    private static final int CASES = 1500;

    private static final String[] TOKENS = {
        "(", ")", ",", ";", "'", "\"", "$$", "--", "/*", "NOT", "NULL", "DEFAULT", "CONSTRAINT", "PRIMARY KEY",
        "REFERENCES", "CHECK (", "INHERITS (", "ALTER TABLE t ", "DROP ", "x", "1e999", "-", "::", "[]", "COLLATE",
        "ONLY", "IF EXISTS", "CASCADE", "\u0000", "é", "99999999999999999999"
    };

    private static List<String> corpus() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/test/resources/golden"))) {
            List<String> corpus = new ArrayList<>();
            for (Path file : files.filter(f -> f.toString().endsWith(".sql")).sorted().toList()) {
                corpus.add(Files.readString(file));
            }
            return corpus;
        }
    }

    private static String mutate(String base, Random random) {
        StringBuilder sql = new StringBuilder(base);
        int mutations = 1 + random.nextInt(8);
        for (int m = 0; m < mutations && !sql.isEmpty(); m++) {
            int at = random.nextInt(sql.length());
            switch (random.nextInt(4)) {
                case 0 -> sql.delete(at, Math.min(sql.length(), at + random.nextInt(40)));
                case 1 -> sql.insert(at, TOKENS[random.nextInt(TOKENS.length)]);
                case 2 -> {
                    int from = random.nextInt(sql.length());
                    sql.insert(at, sql.substring(from, from + Math.min(random.nextInt(60), sql.length() - from)));
                }
                default -> sql.setCharAt(at, (char) (32 + random.nextInt(95)));
            }
        }
        return sql.toString();
    }

    @Test
    void brokenInputNeverMakesAnyStageThrow() throws IOException {
        List<String> corpus = corpus();
        assertThat(corpus).hasSizeGreaterThan(10);
        Random random = new Random(20261116L);
        for (int i = 0; i < CASES; i++) {
            String sql = mutate(corpus.get(random.nextInt(corpus.size())), random);
            String other = corpus.get(random.nextInt(corpus.size()));
            assertThatCode(
                            () -> {
                                Parser.parse(sql);
                                Catalog broken = CatalogBuilder.fromSql(sql).catalog();
                                Catalog sound = CatalogBuilder.fromSql(other).catalog();
                                ReportFormatter.format(RiskAnalyzer.analyze(SchemaDiff.compare(broken, sound)));
                                Planner.plan(broken, sound, PlanOptions.safe()).toSql();
                                Planner.plan(sound, broken).toSql();
                                LintFormatter.format(MigrationLinter.lint(sound, sql));
                                SqlWriter.script(broken);
                            })
                    .as("case %d:\n%s", i, sql)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void applyingAStatementLeavesEveryOtherTableTheSameObject() {
        // The linter diffs the whole schema once per statement. That is only fast because
        // tables a statement does not touch stay the very same objects and are skipped.
        StringBuilder schema = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            schema.append("CREATE TABLE t").append(i).append(" (id int PRIMARY KEY, b int, CONSTRAINT z").append(i).append(" CHECK (b > 0), CONSTRAINT a").append(i).append(" UNIQUE (b));\n");
        }
        Catalog before = CatalogBuilder.fromSql(schema.toString()).catalog();
        CatalogResult after =
                CatalogBuilder.apply(before, Parser.parse("ALTER TABLE t7 ADD COLUMN c int;").statements(), ParserOptions.defaults());
        assertThat(after.diagnostics()).isEmpty();
        for (int i = 0; i < 50; i++) {
            QualifiedName name = new QualifiedName("public", "t" + i);
            if (i == 7) {
                assertThat(after.catalog().tables().get(name)).isNotSameAs(before.tables().get(name));
            } else {
                assertThat(after.catalog().tables().get(name)).as(name.toString()).isSameAs(before.tables().get(name));
            }
        }
        assertThat(SchemaDiff.compare(before, after.catalog()).changes()).hasSize(1);
    }
}
