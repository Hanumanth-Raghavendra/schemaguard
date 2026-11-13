package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.kinds;
import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.rows;
import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.syntaxError;
import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.unsupportedFeature;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic;
import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Severity;
import io.github.hanumanthraghavendra.schemaguard.engine.lexer.LexErrors;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Check;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Column;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Constraint;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Deferrability;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Expression;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ForeignKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.GeneratedColumn;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Identity;
import io.github.hanumanthraghavendra.schemaguard.engine.model.MatchType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Persistence;
import io.github.hanumanthraghavendra.schemaguard.engine.model.PrimaryKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ReferentialAction;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Table;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Unique;
import io.github.hanumanthraghavendra.schemaguard.engine.source.SourceSpan;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ParserTest {

    // ---------------------------------------------------------------- helpers

    /** Parses one CREATE TABLE statement that must produce no diagnostics. */
    private static Table table(String sql) {
        ParseResult result = Parser.parse(sql);
        assertThat(result.diagnostics()).as("diagnostics for: %s", sql).isEmpty();
        assertThat(result.tables()).hasSize(1);
        return result.tables().get(0);
    }

    private static Column column(Table table, String name) {
        return table.column(name).orElseThrow(() -> new AssertionError("No column " + name));
    }

    private static <T extends Constraint> T constraint(Table table, Class<T> type) {
        List<T> matches = table.constraints().stream().filter(type::isInstance).map(type::cast).toList();
        assertThat(matches).as("constraints of type %s", type.getSimpleName()).hasSize(1);
        return matches.get(0);
    }

    private static String defaultOf(String defaultClause) {
        Table table = table("CREATE TABLE t (c text " + defaultClause + ")");
        return column(table, "c").defaultValue().orElseThrow().sql();
    }

    private static String checkOf(String condition) {
        return constraint(table("CREATE TABLE t (a int, CHECK (" + condition + "))"), Check.class)
                .expression()
                .sql();
    }

    // ---------------------------------------------------------------- tests

    @Nested
    class CreateTableBasics {

        @Test
        void parsesColumnsAndInlineConstraints() {
            Table table =
                    table(
                            "CREATE TABLE users (id integer PRIMARY KEY, email varchar(255) NOT NULL UNIQUE, bio text);");

            assertThat(table.name()).isEqualTo(new QualifiedName("public", "users"));
            assertThat(table.persistence()).isEqualTo(Persistence.PERMANENT);
            assertThat(table.columns()).extracting(Column::name).containsExactly("id", "email", "bio");
            assertThat(table.columns())
                    .extracting(c -> c.type().toSql())
                    .containsExactly("integer", "character varying(255)", "text");
            assertThat(table.columns()).extracting(Column::notNull).containsExactly(true, true, false);

            PrimaryKey primaryKey = constraint(table, PrimaryKey.class);
            assertThat(primaryKey.name()).isEmpty();
            assertThat(primaryKey.columns()).containsExactly("id");
            assertThat(table.primaryKey()).contains(primaryKey);
            assertThat(constraint(table, Unique.class).columns()).containsExactly("email");
        }

        @Test
        void emptyTableIsValid() {
            Table table = table("CREATE TABLE t ()");
            assertThat(table.columns()).isEmpty();
            assertThat(table.primaryKey()).isEmpty();
            assertThat(table.column("missing")).isEmpty();
        }

        @Test
        void recordsIfNotExists() {
            ParseResult result = Parser.parse("CREATE TABLE IF NOT EXISTS t (a int)");
            Statement.CreateTable statement = (Statement.CreateTable) result.statements().get(0);
            assertThat(statement.ifNotExists()).isTrue();
            assertThat(((Statement.CreateTable) Parser.parse("CREATE TABLE t (a int)").statements().get(0)).ifNotExists())
                    .isFalse();
        }

        @Test
        void namesFollowPostgresCaseRules() {
            Table table = table("CREATE TABLE \"Order Items\" (\"Id\" int, NAME text, \"select\" text)");
            assertThat(table.name().name()).isEqualTo("Order Items");
            assertThat(table.name().toSql()).isEqualTo("public.\"Order Items\"");
            assertThat(table.columns()).extracting(Column::name).containsExactly("Id", "name", "select");
        }

        @Test
        void qualifiedNamesUseTheLastTwoParts() {
            assertThat(table("CREATE TABLE shop.orders (a int)").name())
                    .isEqualTo(new QualifiedName("shop", "orders"));
            assertThat(table("CREATE TABLE mydb.shop.orders (a int)").name())
                    .isEqualTo(new QualifiedName("shop", "orders"));
        }

        @Test
        void defaultSchemaIsConfigurable() {
            ParseResult result = Parser.parse("CREATE TABLE t (a int REFERENCES other)", new ParserOptions("app"));
            Table table = result.tables().get(0);
            assertThat(table.name().schema()).isEqualTo("app");
            assertThat(constraint(table, ForeignKey.class).referencedTable())
                    .isEqualTo(new QualifiedName("app", "other"));
        }

        @Test
        void persistenceAndTrailingClauses() {
            assertThat(table("CREATE UNLOGGED TABLE t (a int)").persistence()).isEqualTo(Persistence.UNLOGGED);
            assertThat(table("CREATE TEMP TABLE t (a int) ON COMMIT DROP").persistence())
                    .isEqualTo(Persistence.TEMPORARY);
            assertThat(table("CREATE GLOBAL TEMPORARY TABLE t (a int) ON COMMIT PRESERVE ROWS").persistence())
                    .isEqualTo(Persistence.TEMPORARY);
            assertThat(table("CREATE LOCAL TEMP TABLE t (a int) ON COMMIT DELETE ROWS").persistence())
                    .isEqualTo(Persistence.TEMPORARY);

            Table table =
                    table(
                            "CREATE TABLE t (a int) INHERITS (parent, s.other) PARTITION BY RANGE (a, lower(b))"
                                    + " USING heap WITH (fillfactor = 80) WITHOUT OIDS TABLESPACE fast");
            assertThat(table.inherits())
                    .containsExactly(new QualifiedName("public", "parent"), new QualifiedName("s", "other"));
            assertThat(table.partitionBy()).contains("range (a, lower(b))");
        }

        @Test
        void inheritedColumnsAreNotValidated() {
            Table table = table("CREATE TABLE child (PRIMARY KEY (id), NOT NULL id) INHERITS (parent)");
            assertThat(table.primaryKey().orElseThrow().columns()).containsExactly("id");
        }

        @Test
        void aColumnMayBeCalledExclude() {
            assertThat(table("CREATE TABLE t (exclude integer)").columns())
                    .extracting(Column::name)
                    .containsExactly("exclude");
        }
    }

    @Nested
    class Types {

        static Stream<Arguments> typeCases() {
            return rows(
                        """
                        int                             | integer
                        INT4                            | integer
                        int2                            | smallint
                        int8                            | bigint
                        bool                            | boolean
                        float4                          | real
                        float8                          | double precision
                        double precision                | double precision
                        float                           | double precision
                        float(10)                       | real
                        float(30)                       | double precision
                        decimal(10)                     | numeric(10,0)
                        dec                             | numeric
                        numeric(12, 2)                  | numeric(12,2)
                        numeric(5,-2)                   | numeric(5,-2)
                        varchar(50)                     | character varying(50)
                        character varying               | character varying
                        char                            | character(1)
                        char(3)                         | character(3)
                        nchar(2)                        | character(2)
                        national character varying(10)  | character varying(10)
                        national char                   | character(1)
                        bit                             | bit(1)
                        bit varying(8)                  | bit varying(8)
                        varbit(4)                       | bit varying(4)
                        timestamp                       | timestamp without time zone
                        timestamp without time zone     | timestamp without time zone
                        timestamptz                     | timestamp with time zone
                        timestamp(3) with time zone     | timestamp(3) with time zone
                        timetz(2)                       | time(2) with time zone
                        time                            | time without time zone
                        interval                        | interval
                        interval year to month          | interval year to month
                        interval second(3)              | interval second(3)
                        text[]                          | text[]
                        integer[3][]                    | integer[][]
                        integer ARRAY                   | integer[]
                        integer ARRAY[4]                | integer[]
                        uuid                            | uuid
                        serial8                         | bigserial
                        public.order_status             | public.order_status
                        pg_catalog.int4                 | integer
                        pg_catalog.timestamptz          | timestamp with time zone
                        pg_catalog."Odd"                | pg_catalog."Odd"
                        geometry(Point,4326)            | geometry(Point,4326)
                        "MyType"                        | "MyType"
                        "char"                          | "char"
                        "Odd Type"                      | "Odd Type"
                        myschema."Odd Type"[]           | myschema."Odd Type"[]
                        db.myschema.mytype              | myschema.mytype
                        """);
        }

        @ParameterizedTest
        @MethodSource("typeCases")
        void typesAreCanonicalised(String written, String canonical) {
            Table table = table("CREATE TABLE t (c " + written + ")");
            assertThat(column(table, "c").type().toSql()).isEqualTo(canonical);
            assertThat(column(table, "c").type()).hasToString(canonical);
        }

        @Test
        void equivalentSpellingsCompareEqual() {
            Table table = table("CREATE TABLE t (a int, b INTEGER, c pg_catalog.int4, d varchar(5), e character varying(5))");
            assertThat(column(table, "a").type()).isEqualTo(column(table, "b").type()).isEqualTo(column(table, "c").type());
            assertThat(column(table, "d").type()).isEqualTo(column(table, "e").type());
        }
    }

    @Nested
    class ColumnDefinitions {

        private final Table orders =
                table(
                        """
                        CREATE TABLE IF NOT EXISTS shop.orders (
                            id bigint GENERATED ALWAYS AS IDENTITY (START WITH 10 INCREMENT BY 1),
                            customer_id integer NOT NULL CONSTRAINT orders_customer_fk REFERENCES customers (id)
                                ON DELETE CASCADE ON UPDATE RESTRICT DEFERRABLE INITIALLY DEFERRED,
                            status text DEFAULT 'pending'::text NOT NULL,
                            total numeric(12,2) DEFAULT 0.00 CONSTRAINT positive_total CHECK (total >= 0),
                            note text STORAGE EXTERNAL COMPRESSION lz4 COLLATE "C" NULL,
                            doubled numeric GENERATED ALWAYS AS (total * 2) STORED,
                            code char(3) CONSTRAINT code_key UNIQUE NULLS NOT DISTINCT,
                            created_at timestamptz DEFAULT now() CONSTRAINT created_nn NOT NULL NO INHERIT
                        );
                        """);

        @Test
        void identityColumn() {
            Column id = column(orders, "id");
            assertThat(id.identity()).isEqualTo(Identity.ALWAYS);
            assertThat(id.notNull()).as("identity implies NOT NULL").isTrue();
            assertThat(column(table("CREATE TABLE t (a int GENERATED BY DEFAULT AS IDENTITY)"), "a").identity())
                    .isEqualTo(Identity.BY_DEFAULT);
        }

        @Test
        void inlineForeignKey() {
            ForeignKey foreignKey = constraint(orders, ForeignKey.class);
            assertThat(foreignKey.name()).contains("orders_customer_fk");
            assertThat(foreignKey.columns()).containsExactly("customer_id");
            assertThat(foreignKey.referencedTable()).isEqualTo(new QualifiedName("public", "customers"));
            assertThat(foreignKey.referencedColumns()).containsExactly("id");
            assertThat(foreignKey.onDelete()).isEqualTo(ReferentialAction.CASCADE);
            assertThat(foreignKey.onUpdate()).isEqualTo(ReferentialAction.RESTRICT);
            assertThat(foreignKey.match()).isEqualTo(MatchType.SIMPLE);
            assertThat(foreignKey.deferrability()).isEqualTo(Deferrability.DEFERRABLE_INITIALLY_DEFERRED);
            assertThat(foreignKey.notValid()).isFalse();
            assertThat(column(orders, "customer_id").notNull()).isTrue();
        }

        @Test
        void defaultsStopAtTheNextConstraint() {
            assertThat(column(orders, "status").defaultValue()).contains(new Expression("'pending'::text"));
            assertThat(column(orders, "status").notNull()).isTrue();
            assertThat(column(orders, "total").defaultValue()).contains(new Expression("0.00"));
            assertThat(column(orders, "created_at").defaultValue()).contains(new Expression("now()"));
            assertThat(column(orders, "created_at").notNull()).isTrue();
        }

        @Test
        void inlineCheck() {
            Check check = constraint(orders, Check.class);
            assertThat(check.name()).contains("positive_total");
            assertThat(check.expression()).hasToString("total >= 0");
            assertThat(check.noInherit()).isFalse();
            assertThat(check.notValid()).isFalse();
        }

        @Test
        void collationAndStorageClauses() {
            Column note = column(orders, "note");
            assertThat(note.collation()).contains("\"C\"");
            assertThat(note.notNull()).isFalse();
            assertThat(column(table("CREATE TABLE t (a text COLLATE pg_catalog.\"default\")"), "a").collation())
                    .contains("pg_catalog.\"default\"");
        }

        @Test
        void generatedColumn() {
            assertThat(column(orders, "doubled").generated())
                    .contains(new GeneratedColumn(new Expression("total * 2"), true));
            Table virtual = table("CREATE TABLE t (a int, b int GENERATED ALWAYS AS (a + 1) VIRTUAL, c int GENERATED ALWAYS AS (a))");
            assertThat(column(virtual, "b").generated().orElseThrow().stored()).isFalse();
            assertThat(column(virtual, "c").generated().orElseThrow().stored()).isFalse();
        }

        @Test
        void inlineUnique() {
            Unique unique = constraint(orders, Unique.class);
            assertThat(unique.name()).contains("code_key");
            assertThat(unique.columns()).containsExactly("code");
            assertThat(unique.nullsNotDistinct()).isTrue();
            assertThat(constraint(table("CREATE TABLE t (a int UNIQUE NULLS DISTINCT)"), Unique.class).nullsNotDistinct())
                    .isFalse();
        }

        @Test
        void primaryKeyColumnBecomesNotNull() {
            Table table = table("CREATE TABLE t (a int PRIMARY KEY USING INDEX TABLESPACE fast, b int)");
            assertThat(column(table, "a").notNull()).isTrue();
            assertThat(column(table, "b").notNull()).isFalse();
        }
    }

    @Nested
    class TableConstraints {

        private final Table items =
                table(
                        """
                        CREATE TABLE order_items (
                            order_id bigint,
                            line_no int,
                            product_id int,
                            qty int,
                            CONSTRAINT order_items_pkey PRIMARY KEY (order_id, line_no) INCLUDE (qty) WITH (fillfactor = 70),
                            CONSTRAINT order_items_order_fk FOREIGN KEY (order_id) REFERENCES shop.orders
                                MATCH FULL ON DELETE SET NULL (order_id) ON UPDATE NO ACTION,
                            UNIQUE (order_id, product_id) USING INDEX TABLESPACE fast DEFERRABLE,
                            CHECK ((qty > 0) AND (qty < 1000)) NO INHERIT,
                            CONSTRAINT qty_nn NOT NULL qty NO INHERIT
                        );
                        """);

        @Test
        void compositePrimaryKey() {
            PrimaryKey primaryKey = constraint(items, PrimaryKey.class);
            assertThat(primaryKey.name()).contains("order_items_pkey");
            assertThat(primaryKey.columns()).containsExactly("order_id", "line_no");
            assertThat(primaryKey.include()).containsExactly("qty");
            assertThat(primaryKey.deferrability()).isEqualTo(Deferrability.NOT_DEFERRABLE);
            assertThat(items.columns())
                    .extracting(Column::notNull)
                    .as("order_id and line_no via the key, qty via NOT NULL")
                    .containsExactly(true, true, false, true);
        }

        @Test
        void tableLevelForeignKey() {
            ForeignKey foreignKey = constraint(items, ForeignKey.class);
            assertThat(foreignKey.columns()).containsExactly("order_id");
            assertThat(foreignKey.referencedTable()).isEqualTo(new QualifiedName("shop", "orders"));
            assertThat(foreignKey.referencedColumns()).isEmpty();
            assertThat(foreignKey.match()).isEqualTo(MatchType.FULL);
            assertThat(foreignKey.onDelete()).isEqualTo(ReferentialAction.SET_NULL);
            assertThat(foreignKey.onDeleteColumns()).containsExactly("order_id");
            assertThat(foreignKey.onUpdate()).isEqualTo(ReferentialAction.NO_ACTION);
        }

        @Test
        void tableLevelUniqueAndCheck() {
            Unique unique = constraint(items, Unique.class);
            assertThat(unique.columns()).containsExactly("order_id", "product_id");
            assertThat(unique.deferrability()).isEqualTo(Deferrability.DEFERRABLE_INITIALLY_IMMEDIATE);

            Check check = constraint(items, Check.class);
            assertThat(check.expression().sql()).isEqualTo("(qty > 0) and (qty < 1000)");
            assertThat(check.noInherit()).isTrue();
        }

        @Test
        void remainingReferentialActionsAndMatchTypes() {
            Table table =
                    table(
                            "CREATE TABLE t (a int, b int, c int,"
                                    + " FOREIGN KEY (a) REFERENCES o (x) MATCH PARTIAL ON DELETE SET DEFAULT,"
                                    + " FOREIGN KEY (b) REFERENCES o (x) MATCH SIMPLE ON DELETE RESTRICT ON UPDATE SET NULL,"
                                    + " FOREIGN KEY (c) REFERENCES o (x) ON UPDATE SET DEFAULT ON DELETE NO ACTION NOT DEFERRABLE)");
            List<ForeignKey> keys =
                    table.constraints().stream().map(ForeignKey.class::cast).toList();
            assertThat(keys).extracting(ForeignKey::match)
                    .containsExactly(MatchType.PARTIAL, MatchType.SIMPLE, MatchType.SIMPLE);
            assertThat(keys).extracting(ForeignKey::onDelete)
                    .containsExactly(ReferentialAction.SET_DEFAULT, ReferentialAction.RESTRICT, ReferentialAction.NO_ACTION);
            assertThat(keys).extracting(ForeignKey::onUpdate)
                    .containsExactly(ReferentialAction.NO_ACTION, ReferentialAction.SET_NULL, ReferentialAction.SET_DEFAULT);
            assertThat(keys).extracting(ForeignKey::deferrability).containsOnly(Deferrability.NOT_DEFERRABLE);
        }

        @Test
        void deferrabilityVariants() {
            Table table =
                    table(
                            "CREATE TABLE t (a int PRIMARY KEY INITIALLY DEFERRED,"
                                    + " b int UNIQUE DEFERRABLE INITIALLY IMMEDIATE,"
                                    + " c int CHECK (c > 0) ENFORCED)");
            assertThat(constraint(table, PrimaryKey.class).deferrability())
                    .isEqualTo(Deferrability.DEFERRABLE_INITIALLY_DEFERRED);
            assertThat(constraint(table, Unique.class).deferrability())
                    .isEqualTo(Deferrability.DEFERRABLE_INITIALLY_IMMEDIATE);
        }

        @Test
        void notValidIsRecordedOnForeignKeysAndChecks() {
            Table table = table("CREATE TABLE t (a int, FOREIGN KEY (a) REFERENCES o NOT VALID, CHECK (a > 0) NOT VALID)");
            assertThat(constraint(table, ForeignKey.class).notValid()).isTrue();
            assertThat(constraint(table, Check.class).notValid()).isTrue();
        }
    }

    @Nested
    class Expressions {

        @Test
        void defaultsRenderCanonically() {
            assertThat(defaultOf("DEFAULT -1")).isEqualTo("-1");
            assertThat(defaultOf("DEFAULT NULL")).isEqualTo("null");
            assertThat(defaultOf("DEFAULT CURRENT_TIMESTAMP")).isEqualTo("current_timestamp");
            assertThat(defaultOf("DEFAULT (1 + 2) * 3")).isEqualTo("(1 + 2) * 3");
            assertThat(defaultOf("DEFAULT 'it''s'")).isEqualTo("'it''s'");
            assertThat(defaultOf("DEFAULT $$it's$$")).isEqualTo("'it''s'");
            assertThat(defaultOf("DEFAULT '{}'::jsonb NOT NULL")).isEqualTo("'{}'::jsonb");
            assertThat(defaultOf("DEFAULT ARRAY[1,2]::integer[]")).isEqualTo("array[1, 2]::integer[]");
            assertThat(defaultOf("DEFAULT nextval('public.orders_id_seq'::regclass)"))
                    .isEqualTo("nextval('public.orders_id_seq'::regclass)");
        }

        @Test
        void wrappingParenthesesAreRemoved() {
            assertThat(defaultOf("DEFAULT (now() AT TIME ZONE 'utc')")).isEqualTo("now() at time zone 'utc'");
            assertThat(defaultOf("DEFAULT ((1))")).isEqualTo("1");
            assertThat(checkOf("(total >= (0)::numeric)")).isEqualTo("total >= (0)::numeric");
            assertThat(checkOf("(a)::text <> ''::text")).isEqualTo("(a)::text <> ''::text");
            assertThat(checkOf("(a > 0) OR (a < -5)")).isEqualTo("(a > 0) or (a < -5)");
        }

        @Test
        void spacingCaseAndQuotingAreNormalised() {
            assertThat(checkOf("Status   IN('a' ,'b')")).isEqualTo("status in ('a', 'b')");
            assertThat(checkOf("\"Qty\" > 0 AND \"id\" < 5")).isEqualTo("\"Qty\" > 0 and id < 5");
            assertThat(checkOf("a = ANY (ARRAY['x'::text, 'y'::text])"))
                    .isEqualTo("a = any (array['x'::text, 'y'::text])");
            assertThat(checkOf("tags[1] IS NOT NULL")).isEqualTo("tags[1] is not null");
            assertThat(checkOf("public.f(a, -b) > 0")).isEqualTo("public.f(a, -b) > 0");
        }

        @Test
        void unaryAndBinarySignsAreToldApart() {
            assertThat(checkOf("x > - 1")).isEqualTo("x > -1");
            assertThat(checkOf("a - b > 0")).isEqualTo("a - b > 0");
            assertThat(checkOf("a IN (-1, +2)")).isEqualTo("a in (-1, +2)");
            assertThat(checkOf("a BETWEEN -1 AND - 2")).isEqualTo("a between -1 and -2");
            assertThat(checkOf("(a) - 1 > 0")).isEqualTo("(a) - 1 > 0");
        }

        @Test
        void sameExpressionWrittenTwoWaysIsEqual() {
            assertThat(checkOf("TOTAL>=0")).isEqualTo(checkOf("(( total >= 0 ))"));
        }
    }

    @Nested
    class StatementKinds {

        @Test
        void classifiesEveryStatement() {
            ParseResult result =
                    Parser.parse(
                            """
                            SET search_path = public;
                            COMMENT ON TABLE t IS 'x';
                            ALTER TABLE public.t OWNER TO postgres;
                            ALTER DEFAULT PRIVILEGES IN SCHEMA app GRANT SELECT ON TABLES TO reader;
                            CREATE SCHEMA app;
                            CREATE EXTENSION IF NOT EXISTS pgcrypto;
                            CREATE TABLE t (a int);
                            CREATE INDEX i ON t (a);
                            CREATE UNIQUE INDEX u ON t (a);
                            CREATE OR REPLACE FUNCTION f() RETURNS int LANGUAGE sql AS $$ SELECT 1; $$;
                            CREATE MATERIALIZED VIEW mv AS SELECT 1;
                            ALTER TABLE ONLY t ADD COLUMN x int;
                            DROP TABLE IF EXISTS t;
                            UPDATE t SET a = 1;
                            DO $$ BEGIN NULL; END $$;
                            """);

            assertThat(kinds(result))
                    .containsExactly(
                            "ignored:SET",
                            "ignored:COMMENT",
                            "ignored:ALTER TABLE",
                            "ignored:ALTER DEFAULT PRIVILEGES",
                            "ignored:CREATE SCHEMA",
                            "ignored:CREATE EXTENSION",
                            "table:t",
                            "index:i",
                            "index:u",
                            "unsupported:CREATE FUNCTION",
                            "unsupported:CREATE MATERIALIZED VIEW",
                            "alter:t",
                            "drop:TABLE",
                            "unsupported:UPDATE",
                            "unsupported:DO");
            assertThat(result.diagnostics()).hasSize(4);
            assertThat(result.diagnostics()).extracting(Diagnostic::code).containsOnly(ParseErrors.UNSUPPORTED_STATEMENT);
            assertThat(result.diagnostics()).extracting(Diagnostic::severity).containsOnly(Severity.WARNING);
            assertThat(result.diagnostics().get(0).message())
                    .isEqualTo("Unsupported statement: CREATE FUNCTION. It was not analysed.");
            assertThat(result.hasErrors()).isFalse();
        }

        @Test
        void statementsWithInnerSemicolonsStayWhole() {
            ParseResult result =
                    Parser.parse(
                            """
                            CREATE FUNCTION f() RETURNS int LANGUAGE sql
                              BEGIN ATOMIC SELECT 1; SELECT CASE WHEN true THEN 2 ELSE 3 END; END;
                            CREATE RULE r AS ON INSERT TO t DO ALSO (INSERT INTO log VALUES (1); INSERT INTO log VALUES (2));
                            CREATE OR REPLACE RULE r2 AS ON DELETE TO t DO INSTEAD (SELECT 1; SELECT 2);
                            CREATE TABLE after_all (a int);
                            """);
            assertThat(kinds(result))
                    .containsExactly(
                            "unsupported:CREATE FUNCTION",
                            "unsupported:CREATE RULE",
                            "unsupported:CREATE RULE",
                            "table:after_all");
        }

        @Test
        void emptyStatementsAndMissingFinalSemicolonAreFine() {
            ParseResult result = Parser.parse(";;\n\\connect db\nCREATE TABLE a (x int);;  ; CREATE TABLE b (y int)");
            assertThat(kinds(result)).containsExactly("table:a", "table:b");
            assertThat(Parser.parse("").statements()).isEmpty();
            assertThat(Parser.parse("  -- only a comment\n").statements()).isEmpty();
        }

        @Test
        void statementSpansExcludeTheSemicolon() {
            ParseResult result = Parser.parse("SET a = 1;\n  CREATE TABLE t (x int) ;");
            assertThat(result.statements().get(0).span()).isEqualTo(new SourceSpan(0, 9, 1, 1));
            assertThat(result.statements().get(1).span()).isEqualTo(new SourceSpan(13, 35, 2, 3));
        }

        @Test
        void anUnbalancedParenthesisDoesNotSwallowLaterStatements() {
            ParseResult result = Parser.parse("CREATE TABLE broken (a int; CREATE TABLE ok (b int);");
            assertThat(kinds(result)).containsExactly("invalid", "table:ok");
        }

        @Test
        void realisticDump() {
            ParseResult result =
                    Parser.parse(
                            """
                            \\restrict Xq9
                            SET statement_timeout = 0;
                            SELECT pg_catalog.set_config('search_path', '', false);
                            CREATE TYPE public.order_status AS ENUM ('pending', 'shipped');
                            CREATE TABLE public.orders (
                                id bigint NOT NULL,
                                customer_id integer NOT NULL,
                                status public.order_status DEFAULT 'pending'::public.order_status NOT NULL,
                                total numeric(12,2) DEFAULT 0.00,
                                notes text DEFAULT ''::text,
                                created_at timestamp with time zone DEFAULT now() NOT NULL,
                                CONSTRAINT orders_total_check CHECK ((total >= (0)::numeric))
                            );
                            ALTER TABLE public.orders OWNER TO app;
                            CREATE SEQUENCE public.orders_id_seq START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
                            ALTER TABLE ONLY public.orders ALTER COLUMN id SET DEFAULT nextval('public.orders_id_seq'::regclass);
                            ALTER TABLE ONLY public.orders ADD CONSTRAINT orders_pkey PRIMARY KEY (id);
                            CREATE INDEX orders_customer_idx ON public.orders USING btree (customer_id);
                            \\unrestrict Xq9
                            """);

            assertThat(result.diagnostics()).isEmpty();
            assertThat(result.tables()).hasSize(1);
            Table orders = result.tables().get(0);
            assertThat(orders.name()).hasToString("public.orders");
            assertThat(orders.columns()).hasSize(6);
            assertThat(column(orders, "status").type().toSql()).isEqualTo("public.order_status");
            assertThat(column(orders, "status").defaultValue()).contains(new Expression("'pending'::public.order_status"));
            assertThat(column(orders, "notes").defaultValue()).contains(new Expression("''::text"));
            assertThat(column(orders, "created_at").type().toSql()).isEqualTo("timestamp with time zone");
            assertThat(constraint(orders, Check.class).name()).contains("orders_total_check");
            assertThat(kinds(result))
                    .containsExactly(
                            "ignored:SET",
                            "ignored:SELECT",
                            "enum:order_status",
                            "table:orders",
                            "ignored:ALTER TABLE",
                            "sequence:orders_id_seq",
                            "alter:orders",
                            "alter:orders",
                            "index:orders_customer_idx");
        }
    }

    @Nested
    class UnsupportedFeatures {

        static Stream<Arguments> unsupportedCases() {
            return rows(
                        """
                        CREATE TABLE t (LIKE other INCLUDING ALL)                     | LIKE in CREATE TABLE
                        CREATE TABLE t PARTITION OF p FOR VALUES IN (1)               | PARTITION OF
                        CREATE TABLE t OF mytype                                      | typed tables (OF type)
                        CREATE TABLE t AS SELECT 1                                    | CREATE TABLE AS
                        CREATE TABLE t (a int) AS SELECT 1                            | CREATE TABLE AS
                        CREATE TABLE t (a int, EXCLUDE USING gist (a WITH =))         | EXCLUDE constraints
                        CREATE TABLE t (a int, CONSTRAINT c EXCLUDE (a WITH =))       | EXCLUDE constraints
                        CREATE TABLE t (a int CHECK (a > 0) NOT ENFORCED)             | NOT ENFORCED constraints
                        """);
        }

        @ParameterizedTest
        @MethodSource("unsupportedCases")
        void reportsTheFeatureAndKeepsGoing(String sql, String feature) {
            assertThat(unsupportedFeature(sql)).isEqualTo("Unsupported feature: " + feature);
        }

        @Test
        void unsupportedStatementKeepsItsKind() {
            ParseResult result = Parser.parse("CREATE TABLE t (LIKE other)");
            assertThat(kinds(result)).containsExactly("unsupported:CREATE TABLE");
        }
    }

    @Nested
    class SyntaxErrors {

        static Stream<Arguments> syntaxErrorCases() {
            return rows(
                        """
                        CREATE TABLE (a int)                                            | Expected a table name but found '('
                        CREATE TABLE t                                                  | Expected '(' but found end of statement
                        CREATE TABLE t (a int                                           | Expected ',' or ')' but found end of statement
                        CREATE TABLE t (a)                                              | Expected a type name but found ')'
                        CREATE TABLE t (a int, a text)                                  | Column "a" is specified more than once
                        CREATE TABLE t (a int PRIMARY KEY, b int PRIMARY KEY)           | Multiple primary keys for table public.t are not allowed
                        CREATE TABLE t (a int, PRIMARY KEY (b))                         | Column "b" named in a constraint does not exist
                        CREATE TABLE t (a int, NOT NULL b)                              | Column "b" named in a constraint does not exist
                        CREATE TABLE t (a int DEFAULT)                                  | Expected an expression after DEFAULT but found ')'
                        CREATE TABLE t (a int CHECK ())                                 | Expected an expression inside the parentheses
                        CREATE TABLE t (a int CONSTRAINT c)                             | Expected a constraint definition but found ')'
                        CREATE TABLE t (a int, CONSTRAINT c)                            | Expected a constraint definition but found ')'
                        CREATE TABLE t (a int REFERENCES o ON DELETE EXPLODE)           | Expected NO ACTION, RESTRICT, CASCADE, SET NULL or SET DEFAULT but found 'EXPLODE'
                        CREATE TABLE t (a int REFERENCES o MATCH WRONG)                 | Expected FULL, PARTIAL or SIMPLE but found 'WRONG'
                        CREATE TABLE t (a int GENERATED AS IDENTITY)                    | Expected ALWAYS or BY DEFAULT but found 'AS'
                        CREATE TABLE t (a int GENERATED BY DEFAULT AS (1) STORED)       | A generated column must be GENERATED ALWAYS
                        CREATE TABLE t (a int CHECK (a > 0) DEFERRABLE)                 | CHECK constraints cannot be marked DEFERRABLE
                        CREATE TABLE t (a int, PRIMARY KEY (a) NOT VALID)               | PRIMARY KEY constraints cannot be marked NOT VALID
                        CREATE TABLE t (a int UNIQUE NOT VALID)                         | UNIQUE constraints cannot be marked NOT VALID
                        CREATE TABLE t (a int UNIQUE NOT DEFERRABLE INITIALLY DEFERRED) | A constraint declared INITIALLY DEFERRED must be DEFERRABLE
                        CREATE TABLE t (a int UNIQUE NULLS WRONG)                       | Expected DISTINCT but found 'WRONG'
                        CREATE TABLE t (a int) NONSENSE                                 | Unexpected 'NONSENSE' after the table definition
                        CREATE TABLE t (a int) ON COMMIT EXPLODE                        | Expected PRESERVE ROWS, DELETE ROWS or DROP but found 'EXPLODE'
                        CREATE TABLE a.b.c.d (x int)                                    | A name can have at most three parts
                        CREATE TABLE t (a numeric())                                    | Empty type modifier
                        CREATE TABLE t (a int CHECK (a > 0                              | Unbalanced parentheses: this '(' is never closed
                        CREATE TABLE t (a national text)                                | Expected CHARACTER but found 'text'
                        CREATE TABLE t (a double)                                       | Expected PRECISION but found ')'
                        CREATE TABLE t (a int[)                                         | Expected ']' but found ')'
                        CREATE TABLE t (a int ARRAY[2)                                  | Expected ']' but found ')'
                        (SELECT 1)                                                      | Expected a statement but found '('
                        """);
        }

        @ParameterizedTest
        @MethodSource("syntaxErrorCases")
        void reportsAPreciseMessage(String sql, String message) {
            assertThat(syntaxError(sql)).isEqualTo(message);
        }

        @Test
        void errorAtEndOfStatementPointsJustPastTheLastToken() {
            ParseResult result = Parser.parse("CREATE TABLE t (a int");
            assertThat(result.diagnostics().get(0).span()).isEqualTo(new SourceSpan(21, 21, 1, 22));
        }

        @Test
        void errorPointsAtTheOffendingToken() {
            ParseResult result = Parser.parse("CREATE TABLE t (\n  a int,\n  a text)");
            assertThat(result.diagnostics().get(0).span()).isEqualTo(new SourceSpan(28, 29, 3, 3));
        }

        @Test
        void oneBadStatementDoesNotAffectTheNext() {
            ParseResult result = Parser.parse("CREATE TABLE (a int); CREATE TABLE ok (a int);");
            assertThat(kinds(result)).containsExactly("invalid", "table:ok");
            assertThat(result.diagnostics()).hasSize(1);
        }

        @Test
        void lexerDiagnosticsAreCarriedThrough() {
            ParseResult result = Parser.parse("CREATE TABLE t (a text DEFAULT 'oops)");
            assertThat(result.diagnostics())
                    .extracting(Diagnostic::code)
                    .contains(LexErrors.UNTERMINATED_STRING);
            assertThat(result.hasErrors()).isTrue();
        }
    }

    @Nested
    class Api {

        @Test
        void rejectsNullArguments() {
            assertThatThrownBy(() -> Parser.parse(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> Parser.parse("select 1", null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ParserOptions(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void resultListsAreImmutable() {
            ParseResult result = Parser.parse("CREATE TABLE t (a int)");
            assertThatThrownBy(() -> result.statements().clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> result.diagnostics().clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> result.tables().get(0).columns().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        /**
         * The parser's central promise: bad input yields diagnostics, never an exception.
         * Cutting a valid script at every possible position produces thousands of broken inputs.
         */
        @Test
        void neverThrowsOnTruncatedInput() {
            String sql =
                    """
                    CREATE TABLE IF NOT EXISTS shop."Order Items" (
                        id bigint GENERATED BY DEFAULT AS IDENTITY (START WITH 1),
                        ref integer NOT NULL CONSTRAINT fk REFERENCES o (id) MATCH FULL ON DELETE SET NULL (ref) DEFERRABLE,
                        price numeric(12,2) DEFAULT (0.00)::numeric CHECK (price >= 0) NOT VALID,
                        tags text[] DEFAULT ARRAY['a', $q$b$q$]::text[] COLLATE "C",
                        at timestamp(3) with time zone DEFAULT now(), -- comment
                        CONSTRAINT pk PRIMARY KEY (id) INCLUDE (ref) WITH (fillfactor = 70),
                        UNIQUE NULLS NOT DISTINCT (ref, price) INITIALLY DEFERRED
                    ) PARTITION BY RANGE (at) WITH (fillfactor = 80);
                    /* block */ ALTER TABLE x OWNER TO y; COPY t FROM stdin;
                    1\t2
                    \\.
                    CREATE RULE r AS ON INSERT TO t DO ALSO (SELECT 1; SELECT 2);
                    CREATE TYPE mood AS ENUM ('a', 'b'); ALTER TYPE mood ADD VALUE IF NOT EXISTS 'c' AFTER 'b';
                    CREATE TEMP SEQUENCE s AS integer START WITH 1 INCREMENT BY -1 NO MINVALUE MAXVALUE 9 CACHE 1 CYCLE OWNED BY t.a;
                    CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS i ON ONLY t USING btree
                        (a DESC NULLS LAST, lower(b) COLLATE "C" ops (x = 1), (a + 1)) INCLUDE (b)
                        NULLS NOT DISTINCT WITH (fillfactor = 70) TABLESPACE fast WHERE (a > 0);
                    ALTER TABLE IF EXISTS ONLY t ADD COLUMN IF NOT EXISTS c int DEFAULT 1 PRIMARY KEY,
                        ALTER COLUMN c SET DATA TYPE bigint COLLATE "C" USING c::bigint,
                        ALTER c ADD GENERATED BY DEFAULT AS IDENTITY (START WITH 1), ALTER c DROP IDENTITY IF EXISTS,
                        ADD CONSTRAINT u UNIQUE USING INDEX i DEFERRABLE INITIALLY DEFERRED,
                        ADD CONSTRAINT f FOREIGN KEY (c) REFERENCES o (id) NOT VALID, VALIDATE CONSTRAINT f,
                        DROP CONSTRAINT IF EXISTS u CASCADE, RENAME CONSTRAINT f TO g, RENAME COLUMN c TO d, SET SCHEMA x;
                    DROP INDEX CONCURRENTLY IF EXISTS i, j CASCADE; ALTER SEQUENCE IF EXISTS s OWNED BY NONE;
                    """;
            assertThat(Parser.parse(sql).hasErrors()).isFalse();
            for (int end = 0; end <= sql.length(); end++) {
                String truncated = sql.substring(0, end);
                ParseResult result = Parser.parse(truncated);
                assertThat(result.statements()).as("statements for prefix of length %d", end).isNotNull();
            }
        }

        @Test
        void optionalValuesAreEmptyWhenAbsent() {
            Column column = column(table("CREATE TABLE t (a int)"), "a");
            assertThat(column.defaultValue()).isEqualTo(Optional.empty());
            assertThat(column.generated()).isEmpty();
            assertThat(column.collation()).isEmpty();
            assertThat(column.identity()).isEqualTo(Identity.NONE);
        }
    }
}
