package io.github.hanumanthraghavendra.schemaguard.engine.rules;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogBuilder;
import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogResult;
import io.github.hanumanthraghavendra.schemaguard.engine.model.DataType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Expression;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What the engine believes about PostgreSQL against what PostgreSQL 16 was measured to do.
 *
 * <p>The files in {@code src/test/resources/locks} are written by
 * {@code scripts/measure-locks.sh} from a running server. Nothing in them is typed by hand.
 */
class KnowledgeBaseTest {

    /** The data lines of a measurement file, each split at the bars. */
    private static List<String[]> measured(String file) {
        try (InputStream in = KnowledgeBaseTest.class.getResourceAsStream("/locks/" + file)) {
            assertThat(in).as("test resource %s", file).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.startsWith("#") && !line.isBlank())
                    .map(line -> line.split("\\|", -1))
                    .toList();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    // ---------------------------------------------------------------- operations

    static Stream<String[]> operations() {
        return measured("pg16-operations.tsv").stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("operations")
    void everyOperationMatchesItsMeasurement(String name, String lock, String referencedLock, String rewrite, String scan) {
        Operation operation = Operation.valueOf(name);
        assertThat(operation.lock()).as("lock on the changed table").isEqualTo(lock(lock));
        assertThat(operation.referencedLock()).as("lock on the referenced table").isEqualTo(lock(referencedLock));
        TableWork work =
                rewrite.equals("rewrite") ? TableWork.REWRITE : scan.equals("scan") ? TableWork.SCAN : TableWork.NONE;
        assertThat(operation.work()).isEqualTo(work);
    }

    private static Optional<LockLevel> lock(String catalogName) {
        if (catalogName.equals("none")) {
            return Optional.empty();
        }
        Optional<LockLevel> level = LockLevel.fromCatalogName(catalogName);
        assertThat(level).as("lock mode %s", catalogName).isPresent();
        return level;
    }

    @Test
    void everyOperationWasMeasured() {
        assertThat(measured("pg16-operations.tsv").stream().map(row -> row[0]))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(Operation.values()).map(Enum::name).toList());
    }

    @Test
    void aRewriteAlwaysReadsEveryRowAndGrowsWithTheTable() {
        assertThat(TableWork.REWRITE.growsWithTable()).isTrue();
        assertThat(TableWork.SCAN.growsWithTable()).isTrue();
        assertThat(TableWork.NONE.growsWithTable()).isFalse();
        assertThat(Operation.CREATE_INDEX.label()).isEqualTo("CREATE INDEX");
    }

    // ---------------------------------------------------------------- lock modes

    @Test
    void theConflictTableMatchesTheMeasuredOne() {
        List<String[]> rows = measured("pg16-lock-conflicts.tsv");
        assertThat(rows).hasSize(64);
        for (String[] row : rows) {
            LockLevel held = LockLevel.fromSqlName(row[0]).orElseThrow();
            LockLevel requested = LockLevel.fromSqlName(row[1]).orElseThrow();
            assertThat(held.conflictsWith(requested))
                    .as("%s held, %s requested", row[0], row[1])
                    .isEqualTo(row[2].equals("conflict"));
        }
    }

    @Test
    void conflictsAreMutual() {
        for (LockLevel a : LockLevel.values()) {
            for (LockLevel b : LockLevel.values()) {
                assertThat(a.conflictsWith(b)).isEqualTo(b.conflictsWith(a));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
        "ACCESS_EXCLUSIVE,       'blocks reads and writes'",
        "EXCLUSIVE,              'blocks writes, reads continue'",
        "SHARE_ROW_EXCLUSIVE,    'blocks writes, reads continue'",
        "SHARE,                  'blocks writes, reads continue'",
        "SHARE_UPDATE_EXCLUSIVE, 'reads and writes continue, other schema changes wait'",
        "ROW_EXCLUSIVE,          'reads and writes continue'",
        "ACCESS_SHARE,           'reads and writes continue'"
    })
    void eachModeSaysWhatTheApplicationNotices(LockLevel level, String effect) {
        assertThat(level.effect()).isEqualTo(effect);
    }

    @Test
    void lockNamesConvertBothWays() {
        assertThat(LockLevel.SHARE_UPDATE_EXCLUSIVE.sqlName()).isEqualTo("SHARE UPDATE EXCLUSIVE");
        assertThat(LockLevel.SHARE_UPDATE_EXCLUSIVE.catalogName()).isEqualTo("ShareUpdateExclusiveLock");
        assertThat(LockLevel.fromSqlName("  access   exclusive ")).contains(LockLevel.ACCESS_EXCLUSIVE);
        assertThat(LockLevel.fromSqlName("STRONG")).isEmpty();
        assertThat(LockLevel.fromCatalogName("NoSuchLock")).isEmpty();
        for (LockLevel level : LockLevel.values()) {
            assertThat(LockLevel.fromCatalogName(level.catalogName())).contains(level);
            assertThat(LockLevel.fromSqlName(level.sqlName())).contains(level);
        }
    }

    // ---------------------------------------------------------------- type changes

    /** The type as the engine's own parser reads it, so the test covers aliases such as varchar. */
    private static DataType type(String sql) {
        CatalogResult result = CatalogBuilder.fromSql("CREATE TABLE c (x " + sql + ");");
        assertThat(result.diagnostics()).as("diagnostics for type %s", sql).isEmpty();
        return result.catalog().table(new QualifiedName("public", "c")).orElseThrow().column("x").orElseThrow().type();
    }

    static Stream<String[]> typeChanges() {
        return measured("pg16-type-changes.tsv").stream();
    }

    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @MethodSource("typeChanges")
    void everyTypeChangeRewritesExactlyWhenPostgresDid(String from, String to, String measured) {
        assertThat(new TypeChange(type(from), type(to)).rewrites()).isEqualTo(measured.equals("rewrite"));
    }

    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @CsvSource({
        "varchar(10),    varchar(20),      WIDENING",
        "varchar(10),    text,             WIDENING",
        "char(10),       varchar(10),      WIDENING",
        "char,           char(5),          WIDENING",
        "varchar(10),    varchar(5),       NARROWING",
        "text,           varchar(10),      NARROWING",
        "text,           char(3),          NARROWING",
        "smallint,       bigint,           WIDENING",
        "bigint,         integer,          NARROWING",
        "integer,        numeric,          WIDENING",
        "integer,        'numeric(5,2)',   NARROWING",
        "integer,        double precision, WIDENING",
        "bigint,         double precision, NARROWING",
        "real,           double precision, WIDENING",
        "double precision, real,           NARROWING",
        "numeric,        integer,          NARROWING",
        "'numeric(10,2)', 'numeric(12,4)', WIDENING",
        "'numeric(10,2)', 'numeric(10,4)', NARROWING",
        "'numeric(10,2)', 'numeric(8,2)',  NARROWING",
        "numeric,        'numeric(10,2)',  NARROWING",
        "timestamp,      timestamp(3),     NARROWING",
        "timestamp(3),   timestamp,        WIDENING",
        "date,           timestamptz,      WIDENING",
        "timestamp,      date,             NARROWING",
        "timestamp,      timestamptz,      CONVERSION",
        "varbit(10),     varbit(5),        NARROWING",
        "inet,           cidr,             NARROWING",
        "cidr,           inet,             WIDENING",
        "json,           jsonb,            WIDENING",
        "text,           integer,          CONVERSION",
        "integer,        text,             CONVERSION",
        "uuid,           text,             CONVERSION",
        "integer,        integer[],        CONVERSION",
        "varchar(10)[],  text[],           WIDENING",
        "varchar(10)[],  varchar(5)[],     NARROWING",
        "integer,        app.money,        CONVERSION",
        "integer,        integer,          WIDENING"
    })
    void eachTypeChangeSaysWhatHappensToTheValues(String from, String to, TypeChange.Kind kind) {
        DataType target = to.equals("app.money") ? new DataType("app", "money", List.of(), 0) : type(to);
        assertThat(new TypeChange(type(from), target).kind()).isEqualTo(kind);
    }

    @Test
    void aTypeOfAnotherSchemaAlwaysCountsAsARewrite() {
        DataType custom = new DataType("app", "money", List.of(), 0);
        assertThat(new TypeChange(type("integer"), custom).rewrites()).isTrue();
        assertThat(new TypeChange(custom, custom).rewrites()).isFalse();
        assertThat(new TypeChange(new DataType(null, "numeric", List.of("x"), 0), type("numeric(5)")).rewrites()).isTrue();
    }

    // ---------------------------------------------------------------- volatility

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(quoteCharacter = '"', value = {
        "\"0\",                                STABLE",
        "\"'draft'::text\",                    STABLE",
        "\"now()\",                            STABLE",
        "\"CURRENT_TIMESTAMP\",                STABLE",
        "\"lower('A') || md5('x')\",           STABLE",
        "\"(now() + '1 day'::interval)\",      STABLE",
        "\"1::numeric(10,2)\",                 STABLE",
        "\"random()\",                         VOLATILE",
        "\"gen_random_uuid()\",                VOLATILE",
        "\"clock_timestamp()\",                VOLATILE",
        "\"nextval('s'::regclass)\",           VOLATILE",
        "\"pg_catalog.random() * 10\",         VOLATILE",
        "\"uuid_generate_v4()\",               VOLATILE",
        "\"lower(my_function())\",             UNKNOWN",
        "\"app.next_code()\",                  UNKNOWN",
        "\"my_function(random())\",            VOLATILE"
    })
    void defaultsAreClassifiedByTheFunctionsTheyCall(String expression, Volatility expected) {
        assertThat(Volatility.of(new Expression(expression))).isEqualTo(expected);
    }
}
