package io.github.hanumanthraghavendra.schemaguard.engine.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic;
import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Severity;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.Normalized;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Table;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.Expressions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The oracle test. Each fixture is a script and the schema a real PostgreSQL 16 server built
 * from that script, as written back by {@code pg_dump}. If the catalog we build from the
 * script equals the catalog we build from the dump, then our reading of the script (generated
 * names, serial columns, renames, cascading drops) agrees with what the server actually did.
 *
 * <p>Expressions are compared through {@link Normalized}, because the server prints them with
 * extra parentheses and casts.
 */
class CatalogOracleTest {

    private static String resource(String fixture, String name) throws IOException {
        String path = "/golden/" + fixture + "/" + name;
        try (InputStream in = CatalogOracleTest.class.getResourceAsStream(path)) {
            assertThat(in).as("test resource %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<Diagnostic> errors(CatalogResult result) {
        return result.diagnostics().stream().filter(d -> d.severity() == Severity.ERROR).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"names", "migrations"})
    void scriptAndDumpBuildTheSameCatalog(String fixture) throws IOException {
        CatalogResult fromScript = CatalogBuilder.fromSql(resource(fixture, "source.sql"));
        CatalogResult fromDump = CatalogBuilder.fromSql(resource(fixture, "pg_dump_16.sql"));

        assertThat(fromScript.diagnostics()).as("diagnostics for the script").isEmpty();
        assertThat(fromDump.diagnostics()).as("diagnostics for the dump").isEmpty();
        assertSameSchema(fromScript.catalog(), fromDump.catalog());
    }

    @Test
    void shopScriptAndDumpBuildTheSameCatalog() throws IOException {
        CatalogResult fromScript = CatalogBuilder.fromSql(resource("shop", "source.sql"));
        CatalogResult fromDump = CatalogBuilder.fromSql(resource("shop", "pg_dump_16.sql"));

        assertThat(errors(fromScript)).isEmpty();
        assertThat(errors(fromDump)).isEmpty();

        // The script creates one table with PARTITION OF, which the parser does not read yet.
        // The dump writes that table as an ordinary CREATE TABLE, so it appears on one side only.
        Map<QualifiedName, Table> tables = new TreeMap<>(fromDump.catalog().tables());
        assertThat(tables.remove(new QualifiedName("shop", "audit_2026"))).isNotNull();
        Catalog dump = fromDump.catalog();
        assertSameSchema(
                fromScript.catalog(), new Catalog(tables, dump.enums(), dump.sequences(), dump.indexes()));
    }

    // ---------------------------------------------------------------- comparison

    private static void assertSameSchema(Catalog script, Catalog dump) {
        Catalog a = Normalized.catalog(script);
        Catalog b = Normalized.catalog(dump);

        assertThat(a.enums()).as("enumerated types").isEqualTo(b.enums());
        assertThat(a.sequences().keySet()).as("sequence names").isEqualTo(b.sequences().keySet());
        a.sequences().forEach((name, sequence) -> assertThat(sequence).as("sequence %s", name).isEqualTo(b.sequences().get(name)));

        assertThat(a.tables().keySet()).as("table names").isEqualTo(b.tables().keySet());
        a.tables()
                .forEach(
                        (name, table) -> {
                            Table other = b.tables().get(name);
                            assertThat(table.columns()).as("columns of %s", name).containsExactlyElementsOf(other.columns());
                            assertThat(table.constraints())
                                    .as("constraints of %s", name)
                                    .containsExactlyElementsOf(other.constraints());
                            assertThat(table).as("table %s", name).isEqualTo(other);
                        });

        assertThat(a.indexes().keySet()).as("index names").isEqualTo(b.indexes().keySet());
        a.indexes().forEach((name, index) -> assertThat(index).as("index %s", name).isEqualTo(b.indexes().get(name)));
        assertThat(a).isEqualTo(b);
    }
}
