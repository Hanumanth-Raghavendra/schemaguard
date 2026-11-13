package io.github.hanumanthraghavendra.schemaguard.engine.diff;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogBuilder;
import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogResult;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The diff engine against real PostgreSQL output (see {@code golden/README.md}).
 *
 * <p>{@code evolution/expected-diff.txt} is a golden file: the reviewed output for the
 * evolution fixture. After an intended change to the output, rewrite it by running the build
 * with {@code -Dgolden.update=true}, then review the file's diff before committing.
 */
class DiffGoldenTest {

    private static final Path GOLDEN_SOURCE = Path.of("src/test/resources/golden");

    private static String resource(String path) throws IOException {
        try (InputStream in = DiffGoldenTest.class.getResourceAsStream("/golden/" + path)) {
            assertThat(in).as("test resource %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The catalog of the given fixture files run one after another; must build without errors. */
    private static Catalog catalog(String... paths) throws IOException {
        StringBuilder sql = new StringBuilder();
        for (String path : paths) {
            sql.append(resource(path)).append('\n');
        }
        CatalogResult result = CatalogBuilder.fromSql(sql.toString());
        assertThat(result.hasErrors()).as("errors building %s", String.join(" + ", paths)).isFalse();
        return result.catalog();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "shop/pg_dump_16.sql",
                "names/pg_dump_16.sql",
                "migrations/pg_dump_16.sql",
                "evolution/before_pg_dump_16.sql",
                "evolution/after_pg_dump_16.sql"
            })
    void aSchemaComparedWithItselfHasNoChanges(String path) throws IOException {
        Catalog catalog = catalog(path);
        assertThat(SchemaDiff.compare(catalog, catalog).changes()).isEmpty();
        assertThat(SchemaDiff.compare(catalog, catalog(path)).changes()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"names", "migrations"})
    void aScriptAndItsDumpDifferOnlyCosmetically(String fixture) throws IOException {
        SchemaDiff diff = SchemaDiff.compare(catalog(fixture + "/source.sql"), catalog(fixture + "/pg_dump_16.sql"));
        assertThat(diff.significant()).isEmpty();
    }

    @Test
    void theShopScriptAndItsDumpDifferOnlyInThePartitionTheParserSkips() throws IOException {
        SchemaDiff diff = SchemaDiff.compare(catalog("shop/source.sql"), catalog("shop/pg_dump_16.sql"));
        assertThat(diff.significant())
                .singleElement()
                .isInstanceOfSatisfying(
                        Change.TableAdded.class,
                        added -> assertThat(added.table().name()).isEqualTo(new QualifiedName("shop", "audit_2026")));
        assertThat(diff.changes()).as("PostgreSQL prints some defaults and checks differently").isNotEmpty();
    }

    @Test
    void theMigrationScriptBuildsWhatPostgresBuilt() throws IOException {
        SchemaDiff diff =
                SchemaDiff.compare(catalog("evolution/before.sql", "evolution/migration.sql"), catalog("evolution/after_pg_dump_16.sql"));
        assertThat(diff.significant()).isEmpty();
    }

    @Test
    void theEvolutionDiffMatchesTheReviewedGoldenFile() throws IOException {
        SchemaDiff diff =
                SchemaDiff.compare(catalog("evolution/before_pg_dump_16.sql"), catalog("evolution/after_pg_dump_16.sql"));
        String actual = ChangeFormatter.format(diff);
        if (Boolean.getBoolean("golden.update")) {
            Files.writeString(GOLDEN_SOURCE.resolve("evolution/expected-diff.txt"), actual, StandardCharsets.UTF_8);
        }
        assertThat(actual).isEqualTo(resource("evolution/expected-diff.txt"));
        assertThat(diff.significant()).as("dump against dump: nothing is cosmetic").isEqualTo(diff.changes());
    }

    @Test
    void reversingTheComparisonReversesEachChange() throws IOException {
        Catalog before = catalog("evolution/before_pg_dump_16.sql");
        Catalog after = catalog("evolution/after_pg_dump_16.sql");
        SchemaDiff forward = SchemaDiff.compare(before, after);
        SchemaDiff backward = SchemaDiff.compare(after, before);
        assertThat(backward.changes()).hasSameSizeAs(forward.changes());
        assertThat(backward.changes().stream().filter(Change.TableDropped.class::isInstance).count())
                .isEqualTo(forward.changes().stream().filter(Change.TableAdded.class::isInstance).count());
        assertThat(backward.changes().stream().filter(Change.ColumnRenameCandidate.class::isInstance))
                .singleElement()
                .isEqualTo(
                        new Change.ColumnRenameCandidate(
                                new QualifiedName("app", "users"),
                                "full_name",
                                "display_name",
                                0.65,
                                java.util.List.of("same type and attributes", "names 50% similar", "both are column 3")));
    }
}
