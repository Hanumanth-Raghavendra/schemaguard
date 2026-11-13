package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.kinds;
import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.only;
import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.rows;
import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.syntaxError;
import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.unsupportedFeature;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.engine.model.Check;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Column;
import io.github.hanumanthraghavendra.schemaguard.engine.model.DataType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Deferrability;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Expression;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ForeignKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Identity;
import io.github.hanumanthraghavendra.schemaguard.engine.model.PrimaryKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Unique;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AlterTableParserTest {

    private static Statement.AlterTable alter(String sql) {
        return only(sql, Statement.AlterTable.class);
    }

    /** Parses {@code ALTER TABLE t <clause>} and returns its single action. */
    private static AlterAction action(String clause) {
        Statement.AlterTable statement = alter("ALTER TABLE t " + clause);
        assertThat(statement.actions()).hasSize(1);
        return statement.actions().get(0);
    }

    private static Column column(String name, String type, boolean notNull) {
        return new Column(
                name, DataType.of(type), notNull, Optional.empty(), Identity.NONE, Optional.empty(), Optional.empty());
    }

    @Test
    void headerFlagsAndSeveralActions() {
        Statement.AlterTable statement =
                alter(
                        """
                        ALTER TABLE IF EXISTS ONLY shop.orders
                            ADD COLUMN IF NOT EXISTS note text DEFAULT 'x' NOT NULL,
                            DROP COLUMN IF EXISTS old CASCADE,
                            ALTER COLUMN total TYPE numeric(14,2) USING total::numeric,
                            ALTER COLUMN status SET DEFAULT coalesce(fallback, 'new');
                        """);
        assertThat(statement.table()).isEqualTo(new QualifiedName("shop", "orders"));
        assertThat(statement.ifExists()).isTrue();
        assertThat(statement.only()).isTrue();
        assertThat(statement.actions())
                .containsExactly(
                        new AlterAction.AddColumn(
                                new Column(
                                        "note",
                                        DataType.of("text"),
                                        true,
                                        Optional.of(new Expression("'x'")),
                                        Identity.NONE,
                                        Optional.empty(),
                                        Optional.empty()),
                                List.of(),
                                true),
                        new AlterAction.DropColumn("old", true, true),
                        new AlterAction.AlterColumnType(
                                "total",
                                new DataType(null, "numeric", List.of("14", "2"), 0),
                                Optional.empty(),
                                Optional.of(new Expression("total::numeric"))),
                        new AlterAction.SetDefault("status", new Expression("coalesce(fallback, 'new')")));
    }

    @Test
    void plainHeader() {
        Statement.AlterTable statement = alter("ALTER TABLE t DROP COLUMN a");
        assertThat(statement.table()).isEqualTo(new QualifiedName("public", "t"));
        assertThat(statement.ifExists()).isFalse();
        assertThat(statement.only()).isFalse();
    }

    @Test
    void addColumnCarriesItsInlineConstraints() {
        assertThat(action("ADD id bigint PRIMARY KEY"))
                .isEqualTo(
                        new AlterAction.AddColumn(
                                column("id", "bigint", true),
                                List.of(
                                        new PrimaryKey(
                                                Optional.empty(), List.of("id"), List.of(), Deferrability.NOT_DEFERRABLE)),
                                false));

        AlterAction.AddColumn withReference = (AlterAction.AddColumn) action("ADD COLUMN ref int REFERENCES other (id)");
        assertThat(withReference.column()).isEqualTo(column("ref", "integer", false));
        assertThat(withReference.constraints()).hasSize(1).first().isInstanceOf(ForeignKey.class);
        ForeignKey foreignKey = (ForeignKey) withReference.constraints().get(0);
        assertThat(foreignKey.columns()).containsExactly("ref");
        assertThat(foreignKey.referencedTable()).isEqualTo(new QualifiedName("public", "other"));
    }

    @Test
    void aNewColumnMayBeCalledExclude() {
        assertThat(action("ADD exclude int"))
                .isEqualTo(new AlterAction.AddColumn(column("exclude", "integer", false), List.of(), false));
    }

    @Test
    void addConstraint() {
        AlterAction.AddConstraint foreign =
                (AlterAction.AddConstraint) action("ADD CONSTRAINT fk FOREIGN KEY (a) REFERENCES o (id) NOT VALID");
        ForeignKey foreignKey = (ForeignKey) foreign.constraint();
        assertThat(foreignKey.name()).contains("fk");
        assertThat(foreignKey.notValid()).isTrue();

        assertThat(action("ADD PRIMARY KEY (id)"))
                .isEqualTo(
                        new AlterAction.AddConstraint(
                                new PrimaryKey(
                                        Optional.empty(), List.of("id"), List.of(), Deferrability.NOT_DEFERRABLE)));
        assertThat(action("ADD CHECK (a > 0) NOT VALID"))
                .isEqualTo(
                        new AlterAction.AddConstraint(
                                new Check(Optional.empty(), new Expression("a > 0"), false, true)));
        assertThat(action("ADD CONSTRAINT u UNIQUE (a, b) USING INDEX TABLESPACE fast"))
                .isEqualTo(
                        new AlterAction.AddConstraint(
                                new Unique(
                                        Optional.of("u"),
                                        List.of("a", "b"),
                                        List.of(),
                                        false,
                                        Deferrability.NOT_DEFERRABLE)));
    }

    @Test
    void notNullWrittenAsAConstraintIsSetNotNull() {
        assertThat(action("ADD CONSTRAINT nn NOT NULL a")).isEqualTo(new AlterAction.SetNotNull("a"));
    }

    @Test
    void addConstraintUsingAnExistingIndex() {
        assertThat(action("ADD CONSTRAINT orders_pkey PRIMARY KEY USING INDEX orders_pkey_idx"))
                .isEqualTo(
                        new AlterAction.AddConstraintUsingIndex(
                                Optional.of("orders_pkey"), true, "orders_pkey_idx", Deferrability.NOT_DEFERRABLE));
        assertThat(action("ADD UNIQUE USING INDEX u DEFERRABLE INITIALLY DEFERRED"))
                .isEqualTo(
                        new AlterAction.AddConstraintUsingIndex(
                                Optional.empty(), false, "u", Deferrability.DEFERRABLE_INITIALLY_DEFERRED));
        assertThat(action("ADD UNIQUE USING INDEX u DEFERRABLE"))
                .isEqualTo(
                        new AlterAction.AddConstraintUsingIndex(
                                Optional.empty(), false, "u", Deferrability.DEFERRABLE_INITIALLY_IMMEDIATE));
        assertThat(action("ADD UNIQUE USING INDEX u NOT DEFERRABLE INITIALLY IMMEDIATE"))
                .isEqualTo(
                        new AlterAction.AddConstraintUsingIndex(
                                Optional.empty(), false, "u", Deferrability.NOT_DEFERRABLE));
    }

    static Stream<Arguments> singleActions() {
        return Stream.of(
                Arguments.of("DROP a", new AlterAction.DropColumn("a", false, false)),
                Arguments.of("DROP COLUMN a RESTRICT", new AlterAction.DropColumn("a", false, false)),
                Arguments.of("* DROP COLUMN a", new AlterAction.DropColumn("a", false, false)),
                Arguments.of("DROP CONSTRAINT c", new AlterAction.DropConstraint("c", false, false)),
                Arguments.of("DROP CONSTRAINT IF EXISTS c CASCADE", new AlterAction.DropConstraint("c", true, true)),
                Arguments.of(
                        "ALTER a TYPE text",
                        new AlterAction.AlterColumnType("a", DataType.of("text"), Optional.empty(), Optional.empty())),
                Arguments.of(
                        "ALTER COLUMN a SET DATA TYPE varchar(10) COLLATE \"C\" USING a::text",
                        new AlterAction.AlterColumnType(
                                "a",
                                new DataType(null, "character varying", List.of("10"), 0),
                                Optional.of("\"C\""),
                                Optional.of(new Expression("a::text")))),
                Arguments.of("ALTER a SET DEFAULT 0", new AlterAction.SetDefault("a", new Expression("0"))),
                Arguments.of(
                        "ALTER a SET DEFAULT b IS NOT NULL",
                        new AlterAction.SetDefault("a", new Expression("b is not null"))),
                Arguments.of("ALTER a DROP DEFAULT", new AlterAction.DropDefault("a")),
                Arguments.of("ALTER COLUMN a SET NOT NULL", new AlterAction.SetNotNull("a")),
                Arguments.of("ALTER COLUMN a DROP NOT NULL", new AlterAction.DropNotNull("a")),
                Arguments.of(
                        "ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (SEQUENCE NAME public.t_id_seq START WITH 1 CACHE 1)",
                        new AlterAction.AddIdentity("id", Identity.ALWAYS)),
                Arguments.of(
                        "ALTER id ADD GENERATED BY DEFAULT AS IDENTITY",
                        new AlterAction.AddIdentity("id", Identity.BY_DEFAULT)),
                Arguments.of("ALTER id DROP IDENTITY", new AlterAction.DropIdentity("id", false)),
                Arguments.of("ALTER id DROP IDENTITY IF EXISTS", new AlterAction.DropIdentity("id", true)),
                Arguments.of("VALIDATE CONSTRAINT fk", new AlterAction.ValidateConstraint("fk")),
                Arguments.of("RENAME COLUMN a TO b", new AlterAction.RenameColumn("a", "b")),
                Arguments.of("RENAME a TO \"B\"", new AlterAction.RenameColumn("a", "B")),
                Arguments.of("RENAME CONSTRAINT c TO d", new AlterAction.RenameConstraint("c", "d")),
                Arguments.of("RENAME TO t2", new AlterAction.RenameTable("t2")),
                Arguments.of("SET SCHEMA archive", new AlterAction.SetSchema("archive")));
    }

    @ParameterizedTest
    @MethodSource("singleActions")
    void parsesEachAction(String clause, AlterAction expected) {
        assertThat(action(clause)).isEqualTo(expected);
    }

    @Test
    void commasInsideParenthesesDoNotEndAnAction() {
        Statement.AlterTable statement =
                alter("ALTER TABLE t ALTER a SET DEFAULT coalesce(b, ARRAY[1, 2]), ALTER c TYPE int USING f(c, 1), ALTER d DROP DEFAULT");
        assertThat(statement.actions()).hasSize(3);
        assertThat(statement.actions().get(2)).isEqualTo(new AlterAction.DropDefault("d"));
    }

    @Test
    void statementsWrittenByPgDump() {
        ParseResult result =
                Parser.parse(
                        """
                        ALTER TABLE public.orders OWNER TO app;
                        ALTER TABLE public.orders ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
                            SEQUENCE NAME public.orders_id_seq
                            START WITH 1
                            INCREMENT BY 1
                            NO MINVALUE
                            NO MAXVALUE
                            CACHE 1
                        );
                        ALTER TABLE ONLY public.orders ALTER COLUMN legacy_id SET DEFAULT nextval('public.orders_legacy_id_seq'::regclass);
                        ALTER TABLE ONLY public.orders
                            ADD CONSTRAINT orders_pkey PRIMARY KEY (id);
                        ALTER TABLE ONLY public.orders
                            ADD CONSTRAINT orders_customer_fk FOREIGN KEY (customer_id) REFERENCES public.customers(id) ON DELETE CASCADE;
                        """);
        assertThat(result.diagnostics()).isEmpty();
        assertThat(kinds(result))
                .containsExactly("ignored:ALTER TABLE", "alter:orders", "alter:orders", "alter:orders", "alter:orders");
        Statement.AlterTable setDefault = (Statement.AlterTable) result.statements().get(2);
        assertThat(setDefault.only()).isTrue();
        assertThat(setDefault.actions())
                .containsExactly(
                        new AlterAction.SetDefault(
                                "legacy_id", new Expression("nextval('public.orders_legacy_id_seq'::regclass)")));
    }

    static Stream<Arguments> unsupportedCases() {
        return rows(
                """
                ALTER TABLE t ENABLE ROW LEVEL SECURITY                     | ALTER TABLE ... ENABLE ROW
                ALTER TABLE t SET TABLESPACE fast                           | ALTER TABLE ... SET TABLESPACE
                ALTER TABLE t ATTACH PARTITION p FOR VALUES IN (1)          | ALTER TABLE ... ATTACH PARTITION
                ALTER TABLE t REPLICA IDENTITY FULL                         | ALTER TABLE ... REPLICA IDENTITY
                ALTER TABLE t ALTER COLUMN a SET STATISTICS 100             | ALTER TABLE ... ALTER COLUMN SET STATISTICS
                ALTER TABLE t ALTER COLUMN a DROP EXPRESSION                | ALTER TABLE ... ALTER COLUMN DROP EXPRESSION
                ALTER TABLE t ALTER COLUMN a RESET (n_distinct)             | ALTER TABLE ... ALTER COLUMN RESET
                ALTER TABLE t ALTER CONSTRAINT c DEFERRABLE                 | ALTER TABLE ... ALTER CONSTRAINT
                ALTER TABLE t ADD CONSTRAINT c EXCLUDE USING gist (a WITH =) | EXCLUDE constraints
                ALTER TABLE t DROP COLUMN a, CLUSTER ON idx                 | ALTER TABLE ... CLUSTER ON
                """);
    }

    @ParameterizedTest
    @MethodSource("unsupportedCases")
    void otherActionsAreReported(String sql, String feature) {
        assertThat(unsupportedFeature(sql)).isEqualTo("Unsupported feature: " + feature);
    }

    static Stream<Arguments> syntaxErrorCases() {
        return rows(
                """
                ALTER TABLE 5                                           | Expected a table name but found '5'
                ALTER TABLE t                                           | Expected an ALTER TABLE action but found end of statement
                ALTER TABLE t (a)                                       | Expected an ALTER TABLE action but found '('
                ALTER TABLE t ADD COLUMN                                | Expected a column name but found end of statement
                ALTER TABLE t ADD COLUMN a int,                         | Expected an ALTER TABLE action but found end of statement
                ALTER TABLE t ADD a int PRIMARY KEY, ADD b              | Expected a type name but found end of statement
                ALTER TABLE t ADD CONSTRAINT c                          | Expected a constraint definition but found end of statement
                ALTER TABLE t ADD PRIMARY KEY USING INDEX               | Expected an index name but found end of statement
                ALTER TABLE t DROP COLUMN                               | Expected a column name but found end of statement
                ALTER TABLE t DROP CONSTRAINT                           | Expected a constraint name but found end of statement
                ALTER TABLE t DROP COLUMN a b                           | Unexpected 'b' at the end of the statement
                ALTER TABLE t ALTER COLUMN a                            | Expected a column action but found end of statement
                ALTER TABLE t ALTER COLUMN a 5                          | Expected a column action but found '5'
                ALTER TABLE t ALTER COLUMN a SET DEFAULT                | Expected an expression after DEFAULT but found end of statement
                ALTER TABLE t ALTER COLUMN a TYPE                       | Expected a type name but found end of statement
                ALTER TABLE t ALTER COLUMN a TYPE int USING             | Expected an expression after USING but found end of statement
                ALTER TABLE t ALTER COLUMN a ADD GENERATED AS IDENTITY  | Expected ALWAYS or BY DEFAULT but found 'AS'
                ALTER TABLE t ALTER COLUMN a ADD GENERATED ALWAYS AS x  | Expected IDENTITY but found 'x'
                ALTER TABLE t RENAME COLUMN a b                         | Expected TO but found 'b'
                ALTER TABLE t RENAME CONSTRAINT a                       | Expected TO but found end of statement
                ALTER TABLE t RENAME TO                                 | Expected a table name but found end of statement
                ALTER TABLE t VALIDATE CONSTRAINT                       | Expected a constraint name but found end of statement
                ALTER TABLE t SET SCHEMA                                | Expected a schema name but found end of statement
                """);
    }

    @ParameterizedTest
    @MethodSource("syntaxErrorCases")
    void reportsAPreciseMessage(String sql, String message) {
        assertThat(syntaxError(sql)).isEqualTo(message);
    }
}
