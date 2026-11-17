package io.github.hanumanthraghavendra.schemaguard.api.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogBuilder;
import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogResult;
import io.github.hanumanthraghavendra.schemaguard.engine.lint.LintFinding;
import io.github.hanumanthraghavendra.schemaguard.engine.lint.LintFormatter;
import io.github.hanumanthraghavendra.schemaguard.engine.lint.LintReport;
import io.github.hanumanthraghavendra.schemaguard.engine.lint.MigrationLinter;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * SchemaGuard checks its own database migrations. Each Flyway migration is linted against
 * the schema the migrations before it build, and nothing above LOW may be found. A future
 * migration that would lock the analysis table, rewrite it or lose data fails the build here,
 * before it reaches production.
 */
class OwnMigrationsTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    private static List<Path> migrations() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            return files.filter(f -> f.getFileName().toString().matches("V\\d+__\\w+\\.sql"))
                    .sorted((a, b) -> Integer.compare(version(a), version(b)))
                    .toList();
        }
    }

    private static int version(Path file) {
        String name = file.getFileName().toString();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }

    @Test
    void everyMigrationIsSafeForTheSchemaBeforeIt() throws IOException {
        List<Path> files = migrations();
        assertThat(files).isNotEmpty();
        assertThat(files.stream().map(OwnMigrationsTest::version).toList()).as("versions without gaps").isEqualTo(
                java.util.stream.IntStream.rangeClosed(1, files.size()).boxed().toList());

        Catalog schema = Catalog.empty();
        StringBuilder applied = new StringBuilder();
        for (Path file : files) {
            String sql = Files.readString(file, StandardCharsets.UTF_8);
            LintReport report = MigrationLinter.lint(schema, sql);
            assertThat(report.diagnostics()).as("%s: SchemaGuard must understand every statement", file).isEmpty();
            List<LintFinding> serious =
                    report.findings().stream().filter(f -> f.severity().compareTo(RiskLevel.LOW) > 0).toList();
            assertThat(serious).as("%s:\n%s", file, LintFormatter.format(report)).isEmpty();

            applied.append(sql).append('\n');
            CatalogResult result = CatalogBuilder.fromSql(applied.toString());
            assertThat(result.hasErrors()).isFalse();
            schema = result.catalog();
        }
        assertThat(schema.tables()).isNotEmpty();
    }
}
