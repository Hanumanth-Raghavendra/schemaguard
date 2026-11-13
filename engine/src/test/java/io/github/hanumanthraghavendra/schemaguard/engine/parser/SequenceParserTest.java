package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.kinds;
import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.only;
import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.rows;
import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.syntaxError;
import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.unsupportedFeature;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.engine.model.ColumnRef;
import io.github.hanumanthraghavendra.schemaguard.engine.model.DataType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Persistence;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Sequence;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SequenceParserTest {

    private static Statement.CreateSequence create(String sql) {
        return only(sql, Statement.CreateSequence.class);
    }

    private static Optional<ColumnRef> owner(String ownedByClause) {
        return create("CREATE SEQUENCE s OWNED BY " + ownedByClause).sequence().ownedBy();
    }

    @Test
    void formWrittenByPgDump() {
        Statement.CreateSequence statement =
                create(
                        """
                        CREATE SEQUENCE public.orders_id_seq
                            AS integer
                            START WITH 1
                            INCREMENT BY 1
                            NO MINVALUE
                            NO MAXVALUE
                            CACHE 1;
                        """);
        assertThat(statement.ifNotExists()).isFalse();
        assertThat(statement.sequence())
                .isEqualTo(
                        new Sequence(
                                new QualifiedName("public", "orders_id_seq"),
                                Persistence.PERMANENT,
                                Optional.of(DataType.of("integer")),
                                Optional.of(1L),
                                1,
                                Optional.empty(),
                                Optional.empty(),
                                1,
                                false,
                                Optional.empty()));
    }

    @Test
    void aBareSequenceGetsTheDefaults() {
        assertThat(create("CREATE SEQUENCE s").sequence())
                .isEqualTo(
                        new Sequence(
                                new QualifiedName("public", "s"),
                                Persistence.PERMANENT,
                                Optional.empty(),
                                Optional.empty(),
                                1,
                                Optional.empty(),
                                Optional.empty(),
                                1,
                                false,
                                Optional.empty()));
    }

    @Test
    void optionsMayComeInAnyOrder() {
        Statement.CreateSequence statement =
                create(
                        "CREATE TEMP SEQUENCE IF NOT EXISTS countdown OWNED BY shop.orders.id CYCLE CACHE 20"
                                + " MAXVALUE 100 MINVALUE -100 START 100 INCREMENT -5 AS smallint");
        assertThat(statement.ifNotExists()).isTrue();
        assertThat(statement.sequence())
                .isEqualTo(
                        new Sequence(
                                new QualifiedName("public", "countdown"),
                                Persistence.TEMPORARY,
                                Optional.of(DataType.of("smallint")),
                                Optional.of(100L),
                                -5,
                                Optional.of(-100L),
                                Optional.of(100L),
                                20,
                                true,
                                Optional.of(new ColumnRef(new QualifiedName("shop", "orders"), "id"))));
    }

    @Test
    void laterOptionsOverrideEarlierOnes() {
        Sequence sequence =
                create("CREATE UNLOGGED SEQUENCE s MINVALUE 5 NO MINVALUE MAXVALUE 9 NO MAXVALUE CYCLE NO CYCLE START WITH +7")
                        .sequence();
        assertThat(sequence.persistence()).isEqualTo(Persistence.UNLOGGED);
        assertThat(sequence.minValue()).isEmpty();
        assertThat(sequence.maxValue()).isEmpty();
        assertThat(sequence.cycle()).isFalse();
        assertThat(sequence.start()).contains(7L);
        assertThat(create("CREATE TEMPORARY SEQUENCE s").sequence().persistence()).isEqualTo(Persistence.TEMPORARY);
    }

    @Test
    void extremeValuesFit() {
        Sequence sequence =
                create("CREATE SEQUENCE s MINVALUE -9223372036854775808 MAXVALUE 9223372036854775807 CACHE 1_000")
                        .sequence();
        assertThat(sequence.minValue()).contains(Long.MIN_VALUE);
        assertThat(sequence.maxValue()).contains(Long.MAX_VALUE);
        assertThat(sequence.cache()).isEqualTo(1000);
    }

    @Test
    void ownedByForms() {
        assertThat(owner("t.c")).contains(new ColumnRef(new QualifiedName("public", "t"), "c"));
        assertThat(owner("s.t.c")).contains(new ColumnRef(new QualifiedName("s", "t"), "c"));
        assertThat(owner("db.s.t.c")).contains(new ColumnRef(new QualifiedName("s", "t"), "c"));
        assertThat(owner("\"My Table\".\"Id\"")).contains(new ColumnRef(new QualifiedName("public", "My Table"), "Id"));
        assertThat(owner("NONE")).isEmpty();
        assertThat(owner("none.c")).contains(new ColumnRef(new QualifiedName("public", "none"), "c"));
    }

    @Test
    void alterSequenceOwnedBy() {
        Statement.AlterSequenceOwner owned =
                only("ALTER SEQUENCE public.orders_id_seq OWNED BY public.orders.id;", Statement.AlterSequenceOwner.class);
        assertThat(owned.sequence()).isEqualTo(new QualifiedName("public", "orders_id_seq"));
        assertThat(owned.ifExists()).isFalse();
        assertThat(owned.ownedBy()).contains(new ColumnRef(new QualifiedName("public", "orders"), "id"));

        Statement.AlterSequenceOwner released =
                only("ALTER SEQUENCE IF EXISTS s OWNED BY NONE", Statement.AlterSequenceOwner.class);
        assertThat(released.ifExists()).isTrue();
        assertThat(released.ownedBy()).isEmpty();
    }

    @Test
    void ownershipChangesAreIgnored() {
        ParseResult result = Parser.parse("ALTER SEQUENCE s OWNER TO app; ALTER SEQUENCE s OWNED BY t.c;");
        assertThat(result.diagnostics()).isEmpty();
        assertThat(kinds(result)).containsExactly("ignored:ALTER SEQUENCE", "sequence-owner:s");
    }

    static Stream<Arguments> unsupportedCases() {
        return rows(
                """
                ALTER SEQUENCE s RESTART WITH 5                 | ALTER SEQUENCE options other than OWNED BY
                ALTER SEQUENCE s OWNED BY t.c INCREMENT BY 2    | ALTER SEQUENCE options other than OWNED BY
                ALTER SEQUENCE s                                | ALTER SEQUENCE options other than OWNED BY
                """);
    }

    @ParameterizedTest
    @MethodSource("unsupportedCases")
    void otherAlterFormsAreReported(String sql, String feature) {
        assertThat(unsupportedFeature(sql)).isEqualTo("Unsupported feature: " + feature);
    }

    static Stream<Arguments> syntaxErrorCases() {
        return rows(
                """
                CREATE SEQUENCE                                    | Expected a sequence name but found end of statement
                CREATE GLOBAL SEQUENCE s                           | Expected SEQUENCE but found 'GLOBAL'
                CREATE SEQUENCE s INCREMENT BY x                   | Expected an integer but found 'x'
                CREATE SEQUENCE s INCREMENT BY 0                   | INCREMENT must not be zero
                CREATE SEQUENCE s START WITH 99999999999999999999  | Expected an integer but found '99999999999999999999'
                CREATE SEQUENCE s CACHE 1.5                        | Expected an integer but found '1.5'
                CREATE SEQUENCE s MINVALUE -                       | Expected an integer but found end of statement
                CREATE SEQUENCE s BOGUS                            | Unexpected 'BOGUS' in the sequence definition
                CREATE SEQUENCE s OWNED BY t                       | OWNED BY needs a column written as table.column or schema.table.column
                CREATE SEQUENCE s OWNED BY a.b.c.d.e               | OWNED BY needs a column written as table.column or schema.table.column
                CREATE SEQUENCE s OWNED BY 5                       | Expected a table name but found '5'
                ALTER SEQUENCE 5                                   | Expected a sequence name but found '5'
                """);
    }

    @ParameterizedTest
    @MethodSource("syntaxErrorCases")
    void reportsAPreciseMessage(String sql, String message) {
        assertThat(syntaxError(sql)).isEqualTo(message);
    }
}
