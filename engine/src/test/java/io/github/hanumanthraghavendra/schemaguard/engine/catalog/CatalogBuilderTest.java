package io.github.hanumanthraghavendra.schemaguard.engine.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic;
import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Severity;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Check;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Column;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ColumnRef;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Constraint;
import io.github.hanumanthraghavendra.schemaguard.engine.model.DataType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Expression;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ForeignKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Identity;
import io.github.hanumanthraghavendra.schemaguard.engine.model.PrimaryKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Sequence;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Table;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.ParseErrors;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserOptions;
import io.github.hanumanthraghavendra.schemaguard.engine.source.SourceSpan;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CatalogBuilderTest {

    // ---------------------------------------------------------------- helpers

    /** Builds a catalog from a script that must produce no diagnostics. */
    private static Catalog catalog(String sql) {
        CatalogResult result = CatalogBuilder.fromSql(sql);
        assertThat(result.diagnostics()).as("diagnostics for: %s", sql).isEmpty();
        return result.catalog();
    }

    private static QualifiedName q(String name) {
        return new QualifiedName("public", name);
    }

    private static Table table(Catalog catalog, String name) {
        return catalog.table(q(name)).orElseThrow(() -> new AssertionError("No table " + name));
    }

    private static Column column(Catalog catalog, String table, String column) {
        return table(catalog, table).column(column).orElseThrow(() -> new AssertionError("No column " + column));
    }

    private static List<String> constraintNames(Catalog catalog, String table) {
        return table(catalog, table).constraints().stream().map(c -> c.name().orElseThrow()).toList();
    }

    private static List<String> columnNames(Catalog catalog, String table) {
        return table(catalog, table).columns().stream().map(Column::name).toList();
    }

    /** Turns lines of "script | expected message" into test arguments. */
    private static Stream<Arguments> rows(String block) {
        return block.lines()
                .filter(line -> !line.isBlank())
                .map(
                        line -> {
                            int bar = line.lastIndexOf('|');
                            return Arguments.of(line.substring(0, bar).strip(), line.substring(bar + 1).strip());
                        });
    }

    // ---------------------------------------------------------------- errors

    /** Every message here is PostgreSQL 16's own wording for the same mistake. */
    static Stream<Arguments> errorCases() {
        return rows(
                """
                CREATE TABLE t (a int); CREATE TABLE t (b int)                                                                  | relation "t" already exists
                CREATE TABLE t (a int); CREATE SEQUENCE t                                                                       | relation "t" already exists
                CREATE TABLE t (a int PRIMARY KEY); CREATE INDEX t_pkey ON t (a)                                                | relation "t_pkey" already exists
                CREATE TABLE t (a int); CREATE INDEX i ON t (a); CREATE INDEX i ON t (a)                                        | relation "i" already exists
                CREATE TABLE t (a int); CREATE INDEX ON t (nope)                                                                | column "nope" does not exist
                CREATE TABLE t (a int); CREATE INDEX ON t (a) INCLUDE (nope)                                                    | column "nope" does not exist
                CREATE INDEX ON nope (a)                                                                                        | relation "nope" does not exist
                ALTER TABLE nope ADD COLUMN x int                                                                               | relation "nope" does not exist
                CREATE TABLE t (a int); ALTER TABLE t ADD COLUMN a int                                                          | column "a" of relation "t" already exists
                CREATE TABLE t (a int); ALTER TABLE t DROP COLUMN nope                                                          | column "nope" of relation "t" does not exist
                CREATE TABLE t (a int); ALTER TABLE t DROP CONSTRAINT nope                                                      | constraint "nope" of relation "t" does not exist
                CREATE TABLE t (a int); ALTER TABLE t VALIDATE CONSTRAINT nope                                                  | constraint "nope" of relation "t" does not exist
                CREATE TABLE t (a int PRIMARY KEY); ALTER TABLE t VALIDATE CONSTRAINT t_pkey                                    | constraint "t_pkey" of relation "t" is not a foreign key or check constraint
                CREATE TABLE t (a int UNIQUE); ALTER TABLE t VALIDATE CONSTRAINT t_a_key                                        | constraint "t_a_key" of relation "t" is not a foreign key or check constraint
                CREATE TABLE t (a int PRIMARY KEY); ALTER TABLE t ALTER COLUMN a DROP NOT NULL                                  | column "a" is in a primary key
                CREATE TABLE t (a int GENERATED ALWAYS AS IDENTITY); ALTER TABLE t ALTER a DROP NOT NULL                        | column "a" of relation "t" is an identity column
                CREATE TABLE t (a int); ALTER TABLE t ALTER a ADD GENERATED ALWAYS AS IDENTITY                                  | column "a" of relation "t" must be declared NOT NULL before identity can be added
                CREATE TABLE t (a int GENERATED ALWAYS AS IDENTITY); ALTER TABLE t ALTER a ADD GENERATED ALWAYS AS IDENTITY     | column "a" of relation "t" is already an identity column
                CREATE TABLE t (a int NOT NULL DEFAULT 1); ALTER TABLE t ALTER a ADD GENERATED ALWAYS AS IDENTITY               | column "a" of relation "t" already has a default value
                CREATE TABLE t (a int); ALTER TABLE t ALTER a DROP IDENTITY                                                     | column "a" of relation "t" is not an identity column
                CREATE TABLE t (a int); ALTER TABLE t ALTER nope SET NOT NULL                                                   | column "nope" of relation "t" does not exist
                CREATE TABLE t (a int PRIMARY KEY, b int); ALTER TABLE t ADD PRIMARY KEY (b)                                    | multiple primary keys for table "t" are not allowed
                CREATE TABLE t (a int); ALTER TABLE t ADD UNIQUE (nope)                                                         | column "nope" named in key does not exist
                CREATE TABLE t (a int); ALTER TABLE t ADD UNIQUE (a) INCLUDE (nope)                                             | column "nope" named in key does not exist
                CREATE TABLE t (a int); ALTER TABLE t ADD PRIMARY KEY (nope)                                                    | column "nope" of relation "t" does not exist
                CREATE TABLE t (a int); ALTER TABLE t ADD PRIMARY KEY (a) INCLUDE (nope)                                        | column "nope" named in key does not exist
                CREATE TABLE t (a int PRIMARY KEY); ALTER TABLE t ADD CONSTRAINT t_pkey CHECK (a > 0)                           | constraint "t_pkey" for relation "t" already exists
                CREATE TABLE t (a int); CREATE TABLE other (b int); ALTER TABLE t ADD CONSTRAINT other UNIQUE (a)               | relation "other" already exists
                CREATE TABLE t (a int); ALTER TABLE t ADD FOREIGN KEY (a) REFERENCES nope                                       | relation "nope" does not exist
                CREATE TABLE p (id int PRIMARY KEY); CREATE TABLE t (a int); ALTER TABLE t ADD FOREIGN KEY (nope) REFERENCES p  | column "nope" referenced in foreign key constraint does not exist
                CREATE TABLE p (id int PRIMARY KEY); CREATE TABLE t (a int); ALTER TABLE t ADD FOREIGN KEY (a) REFERENCES p (nope) | column "nope" referenced in foreign key constraint does not exist
                CREATE TABLE p (id int); CREATE TABLE t (a int REFERENCES p)                                                    | there is no primary key for referenced table "p"
                CREATE TABLE p (id int, v int); CREATE TABLE t (a int REFERENCES p (v))                                         | there is no unique constraint matching given keys for referenced table "p"
                CREATE TABLE p (a int, b int, PRIMARY KEY (a, b)); CREATE TABLE t (x int REFERENCES p)                          | number of referencing and referenced columns for foreign key disagree
                CREATE TABLE t (a int, b int); ALTER TABLE t RENAME COLUMN a TO b                                               | column "b" of relation "t" already exists
                CREATE TABLE t (a int); ALTER TABLE t RENAME COLUMN nope TO z                                                   | column "nope" does not exist
                CREATE TABLE t (a int); ALTER TABLE t RENAME CONSTRAINT nope TO z                                               | constraint "nope" for table "t" does not exist
                CREATE TABLE t (a int PRIMARY KEY CHECK (a > 0)); ALTER TABLE t RENAME CONSTRAINT t_pkey TO t_a_check           | constraint "t_a_check" for relation "t" already exists
                CREATE TABLE t (a int PRIMARY KEY); CREATE TABLE other (b int); ALTER TABLE t RENAME CONSTRAINT t_pkey TO other | relation "other" already exists
                CREATE TABLE t (a int); CREATE TABLE other (b int); ALTER TABLE t RENAME TO other                               | relation "other" already exists
                CREATE TABLE t (a int); ALTER TABLE t RENAME TO t                                                               | relation "t" already exists
                CREATE TABLE app.h (a int); CREATE TABLE h (a int); ALTER TABLE h SET SCHEMA app                                | relation "h" already exists in schema "app"
                CREATE TABLE t (a int); ALTER TABLE t ADD CONSTRAINT x UNIQUE USING INDEX nope                                  | index "nope" does not exist
                CREATE TABLE t (a int); CREATE INDEX i ON t (a); ALTER TABLE t ADD CONSTRAINT x UNIQUE USING INDEX i            | "i" is not a unique index
                CREATE TABLE t (a int PRIMARY KEY); ALTER TABLE t ADD CONSTRAINT x UNIQUE USING INDEX t_pkey                    | index "t_pkey" is already associated with a constraint
                CREATE TABLE t (a int); CREATE UNIQUE INDEX i ON t (a) WHERE a > 0; ALTER TABLE t ADD CONSTRAINT x UNIQUE USING INDEX i | "i" is a partial index
                CREATE TABLE t (a int); CREATE UNIQUE INDEX i ON t ((a + 1)); ALTER TABLE t ADD CONSTRAINT x UNIQUE USING INDEX i | index "i" contains expressions
                CREATE TABLE t (a int); CREATE TABLE o (a int); CREATE UNIQUE INDEX i ON o (a); ALTER TABLE t ADD CONSTRAINT x UNIQUE USING INDEX i | index "i" does not belong to table "t"
                CREATE TABLE p (id int PRIMARY KEY); CREATE TABLE c (p_id int REFERENCES p); DROP TABLE p                       | cannot drop table p because other objects depend on it
                CREATE TABLE p (id int PRIMARY KEY, u int UNIQUE); CREATE TABLE c (p_u int REFERENCES p (u)); ALTER TABLE p DROP COLUMN u | cannot drop column u of table p because other objects depend on it
                CREATE TABLE s (id int PRIMARY KEY, parent int REFERENCES s); ALTER TABLE s DROP COLUMN id                      | cannot drop column id of table s because other objects depend on it
                CREATE TABLE t (a int, g int GENERATED ALWAYS AS (a * 2) STORED); ALTER TABLE t DROP COLUMN a                   | cannot drop column a of table t because other objects depend on it
                CREATE TABLE p (id int PRIMARY KEY); CREATE TABLE c (p_id int REFERENCES p); ALTER TABLE p DROP CONSTRAINT p_pkey | cannot drop constraint p_pkey on table p because other objects depend on it
                CREATE TABLE t (a int PRIMARY KEY); DROP INDEX t_pkey                                                           | cannot drop index t_pkey because constraint t_pkey on table t requires it
                CREATE TABLE e (v int); CREATE UNIQUE INDEX e_v ON e (v); CREATE TABLE f (x int REFERENCES e (v)); DROP INDEX e_v | cannot drop index e_v because other objects depend on it
                DROP INDEX nope                                                                                                 | index "nope" does not exist
                DROP TABLE nope                                                                                                 | table "nope" does not exist
                DROP SEQUENCE nope                                                                                              | sequence "nope" does not exist
                DROP TYPE nope                                                                                                  | type "nope" does not exist
                CREATE TYPE mood AS ENUM ('a'); CREATE TYPE mood AS ENUM ('b')                                                  | type "mood" already exists
                CREATE TABLE mood (a int); CREATE TYPE mood AS ENUM ('b')                                                       | type "mood" already exists
                CREATE TYPE mood AS ENUM ('a'); ALTER TYPE mood ADD VALUE 'a'                                                   | enum label "a" already exists
                CREATE TYPE mood AS ENUM ('a'); ALTER TYPE mood ADD VALUE 'x' BEFORE 'nope'                                     | "nope" is not an existing enum label
                CREATE TYPE mood AS ENUM ('a'); ALTER TYPE mood ADD VALUE 'x' AFTER 'nope'                                      | "nope" is not an existing enum label
                CREATE TYPE mood AS ENUM ('a'); ALTER TYPE mood RENAME VALUE 'nope' TO 'y'                                      | "nope" is not an existing enum label
                CREATE TYPE mood AS ENUM ('a', 'b'); ALTER TYPE mood RENAME VALUE 'a' TO 'b'                                    | enum label "b" already exists
                ALTER TYPE nope ADD VALUE 'x'                                                                                   | type "nope" does not exist
                CREATE TYPE mood AS ENUM ('a'); CREATE TABLE t (m mood); DROP TYPE mood                                         | cannot drop type mood because other objects depend on it
                CREATE TABLE t (id serial); DROP SEQUENCE t_id_seq                                                              | cannot drop sequence t_id_seq because other objects depend on it
                ALTER SEQUENCE nope OWNED BY t.a                                                                                | relation "nope" does not exist
                CREATE SEQUENCE s; ALTER SEQUENCE s OWNED BY nope.a                                                             | relation "nope" does not exist
                CREATE TABLE t (a int); CREATE SEQUENCE s OWNED BY t.nope                                                       | column "nope" of relation "t" does not exist
                CREATE SEQUENCE s AS text                                                                                       | sequence type must be smallint, integer, or bigint
                """);
    }

    @ParameterizedTest
    @MethodSource("errorCases")
    void reportsTheErrorPostgresReports(String script, String message) {
        CatalogResult result = CatalogBuilder.fromSql(script);

        assertThat(result.diagnostics()).as("diagnostics for: %s", script).hasSize(1);
        Diagnostic diagnostic = result.diagnostics().get(0);
        assertThat(diagnostic.message()).isEqualTo(message);
        assertThat(diagnostic.severity()).isEqualTo(Severity.ERROR);
        assertThat(diagnostic.code()).startsWith("SG-CAT-");
        assertThat(result.hasErrors()).isTrue();
    }

    @Nested
    class Diagnostics {

        @Test
        void eachKindOfErrorHasItsOwnCode() {
            assertThat(code("CREATE TABLE t (a int); CREATE TABLE t (a int)")).isEqualTo(CatalogErrors.DUPLICATE_OBJECT);
            assertThat(code("DROP TABLE nope")).isEqualTo(CatalogErrors.UNDEFINED_OBJECT);
            assertThat(code("CREATE TABLE t (a int); ALTER TABLE t DROP COLUMN b")).isEqualTo(CatalogErrors.UNDEFINED_COLUMN);
            assertThat(code("CREATE TABLE t (a int); ALTER TABLE t ADD COLUMN a int")).isEqualTo(CatalogErrors.DUPLICATE_COLUMN);
            assertThat(code("CREATE TABLE t (a int PRIMARY KEY); DROP INDEX t_pkey")).isEqualTo(CatalogErrors.DEPENDENT_OBJECTS);
            assertThat(code("CREATE SEQUENCE s AS text")).isEqualTo(CatalogErrors.INVALID_DEFINITION);
        }

        private String code(String script) {
            return CatalogBuilder.fromSql(script).diagnostics().get(0).code();
        }

        @Test
        void anErrorPointsAtTheStatementThatFailed() {
            CatalogResult result = CatalogBuilder.fromSql("CREATE TABLE t (a int);\n  DROP TABLE nope;");
            assertThat(result.diagnostics().get(0).span()).isEqualTo(new SourceSpan(26, 41, 2, 3));
        }

        @Test
        void parserDiagnosticsComeFirstAndWarningsAreNotErrors() {
            CatalogResult mixed = CatalogBuilder.fromSql("CREATE VIEW v AS SELECT 1; DROP TABLE nope;");
            assertThat(mixed.diagnostics())
                    .extracting(Diagnostic::code)
                    .containsExactly(ParseErrors.UNSUPPORTED_STATEMENT, CatalogErrors.UNDEFINED_OBJECT);
            assertThat(mixed.hasErrors()).isTrue();

            CatalogResult warningOnly = CatalogBuilder.fromSql("CREATE VIEW v AS SELECT 1; CREATE TABLE t (a int);");
            assertThat(warningOnly.diagnostics()).hasSize(1);
            assertThat(warningOnly.hasErrors()).isFalse();
            assertThat(warningOnly.catalog().tables()).containsKey(q("t"));
        }
    }

    // ---------------------------------------------------------------- behaviour

    @Nested
    class Atomicity {

        @Test
        void aFailedStatementChangesNothingAndLaterStatementsStillRun() {
            CatalogResult result =
                    CatalogBuilder.fromSql(
                            """
                            CREATE TABLE t (a int);
                            ALTER TABLE t ADD COLUMN b int, ADD COLUMN c serial, DROP COLUMN nope;
                            ALTER TABLE t ADD COLUMN d int;
                            """);
            assertThat(result.diagnostics()).hasSize(1);
            assertThat(columnNames(result.catalog(), "t")).containsExactly("a", "d");
            assertThat(result.catalog().sequences()).as("the serial column's sequence is rolled back too").isEmpty();
        }

        @Test
        void ifExistsAndIfNotExistsSkipSilently() {
            Catalog catalog =
                    catalog(
                            """
                            CREATE TABLE t (a int NOT NULL);
                            CREATE TABLE IF NOT EXISTS t (zzz int);
                            CREATE INDEX i ON t (a);
                            CREATE INDEX IF NOT EXISTS i ON t (a, a);
                            CREATE SEQUENCE s;
                            CREATE SEQUENCE IF NOT EXISTS s INCREMENT 5;
                            CREATE TYPE mood AS ENUM ('a');
                            ALTER TYPE mood ADD VALUE IF NOT EXISTS 'a';
                            ALTER TABLE IF EXISTS nope ADD COLUMN x int;
                            ALTER TABLE t ADD COLUMN IF NOT EXISTS a text, DROP COLUMN IF EXISTS nope,
                                DROP CONSTRAINT IF EXISTS nope, ALTER a DROP IDENTITY IF EXISTS;
                            ALTER SEQUENCE IF EXISTS nope OWNED BY t.a;
                            DROP TABLE IF EXISTS nope;
                            DROP INDEX IF EXISTS nope;
                            DROP SEQUENCE IF EXISTS nope;
                            DROP TYPE IF EXISTS nope;
                            """);
            assertThat(column(catalog, "t", "a").type()).isEqualTo(DataType.of("integer"));
            assertThat(catalog.indexes().get(q("i")).columns()).hasSize(1);
            assertThat(catalog.sequences().get(q("s")).increment()).isEqualTo(1);
            assertThat(catalog.enums().get(q("mood")).labels()).containsExactly("a");
        }
    }

    @Nested
    class ColumnsAndTypes {

        @Test
        void serialExpandsIntoAnIntegerASequenceAndADefault() {
            Catalog catalog = catalog("CREATE UNLOGGED TABLE t (id serial, big bigserial, small smallserial)");

            Column id = column(catalog, "t", "id");
            assertThat(id.type()).isEqualTo(DataType.of("integer"));
            assertThat(id.notNull()).isTrue();
            assertThat(id.defaultValue()).contains(new Expression("nextval('public.t_id_seq'::regclass)"));
            assertThat(column(catalog, "t", "big").type()).isEqualTo(DataType.of("bigint"));
            assertThat(column(catalog, "t", "small").type()).isEqualTo(DataType.of("smallint"));

            Sequence sequence = catalog.sequences().get(q("t_id_seq"));
            assertThat(sequence.dataType()).contains(DataType.of("integer"));
            assertThat(sequence.maxValue()).contains((long) Integer.MAX_VALUE);
            assertThat(sequence.ownedBy()).contains(new ColumnRef(q("t"), "id"));
            assertThat(sequence.persistence()).as("follows the table").isEqualTo(table(catalog, "t").persistence());
            assertThat(catalog.sequences().get(q("t_big_seq")).maxValue()).contains(Long.MAX_VALUE);
            assertThat(catalog.sequences().get(q("t_small_seq")).maxValue()).contains((long) Short.MAX_VALUE);
        }

        @Test
        void aSerialSequenceAvoidsNamesThatAreTaken() {
            Catalog catalog = catalog("CREATE TABLE t_id_seq (x int); CREATE TABLE t (id serial);");
            assertThat(catalog.sequences()).containsOnlyKeys(q("t_id_seq1"));
        }

        @Test
        void unqualifiedEnumAndSequenceNamesAreResolved() {
            Catalog catalog =
                    catalog(
                            """
                            CREATE TYPE mood AS ENUM ('a');
                            CREATE SEQUENCE counter;
                            CREATE TABLE t (
                                m mood, many mood[], other_type citext,
                                n bigint DEFAULT nextval('counter'),
                                unknown bigint DEFAULT nextval('nope'),
                                odd1 bigint DEFAULT nextval(''), odd2 bigint DEFAULT nextval('a.'),
                                odd3 bigint DEFAULT nextval('a b'), odd4 bigint DEFAULT nextval('1'),
                                later bigint
                            );
                            ALTER TABLE t ALTER COLUMN later SET DEFAULT nextval('public."counter"');
                            """);
            assertThat(column(catalog, "t", "m").type().toSql()).isEqualTo("public.mood");
            assertThat(column(catalog, "t", "many").type().toSql()).isEqualTo("public.mood[]");
            assertThat(column(catalog, "t", "other_type").type().toSql()).isEqualTo("citext");
            Expression canonical = new Expression("nextval('public.counter'::regclass)");
            assertThat(column(catalog, "t", "n").defaultValue()).contains(canonical);
            assertThat(column(catalog, "t", "later").defaultValue()).contains(canonical);
            assertThat(column(catalog, "t", "unknown").defaultValue()).contains(new Expression("nextval('nope')"));
            assertThat(column(catalog, "t", "odd2").defaultValue()).contains(new Expression("nextval('a.')"));
        }

        @Test
        void collationsAreReducedToOneSpelling() {
            Catalog catalog =
                    catalog(
                            """
                            CREATE TABLE t (a text COLLATE pg_catalog."C", b text COLLATE "default", c text COLLATE "de_DE", d text);
                            ALTER TABLE t ALTER c TYPE varchar(10), ALTER d TYPE text COLLATE "C";
                            CREATE INDEX i ON t (a COLLATE pg_catalog."C", b);
                            """);
            assertThat(column(catalog, "t", "a").collation()).contains("\"C\"");
            assertThat(column(catalog, "t", "b").collation()).isEmpty();
            assertThat(column(catalog, "t", "c").collation()).as("changing the type resets the collation").isEmpty();
            assertThat(column(catalog, "t", "c").type().toSql()).isEqualTo("character varying(10)");
            assertThat(column(catalog, "t", "d").collation()).contains("\"C\"");
            assertThat(catalog.indexes().get(q("i")).columns().get(0).collation()).contains("\"C\"");
        }

        @Test
        void nullabilityDefaultsAndIdentityCanBeChanged() {
            Catalog catalog =
                    catalog(
                            """
                            CREATE TABLE t (a int NOT NULL, b int DEFAULT 1, c int NOT NULL);
                            ALTER TABLE t ALTER a DROP NOT NULL, ALTER b SET NOT NULL, ALTER b DROP DEFAULT;
                            ALTER TABLE t ALTER c ADD GENERATED BY DEFAULT AS IDENTITY;
                            """);
            assertThat(column(catalog, "t", "a").notNull()).isFalse();
            assertThat(column(catalog, "t", "b").notNull()).isTrue();
            assertThat(column(catalog, "t", "b").defaultValue()).isEmpty();
            assertThat(column(catalog, "t", "c").identity()).isEqualTo(Identity.BY_DEFAULT);

            Catalog dropped = catalog("CREATE TABLE t (c int GENERATED ALWAYS AS IDENTITY); ALTER TABLE t ALTER c DROP IDENTITY;");
            assertThat(column(dropped, "t", "c").identity()).isEqualTo(Identity.NONE);
            assertThat(column(dropped, "t", "c").notNull()).as("dropping identity keeps NOT NULL").isTrue();
        }
    }

    @Nested
    class Sequences {

        @Test
        void unspecifiedValuesGetPostgresDefaults() {
            Catalog catalog =
                    catalog(
                            """
                            CREATE SEQUENCE up;
                            CREATE SEQUENCE down INCREMENT -1;
                            CREATE SEQUENCE small AS smallint INCREMENT -2;
                            CREATE SEQUENCE explicit AS integer MINVALUE 5 MAXVALUE 50 START 7;
                            """);
            Sequence up = catalog.sequences().get(q("up"));
            assertThat(up.dataType()).contains(DataType.of("bigint"));
            assertThat(List.of(up.start().orElseThrow(), up.minValue().orElseThrow(), up.maxValue().orElseThrow()))
                    .containsExactly(1L, 1L, Long.MAX_VALUE);

            Sequence down = catalog.sequences().get(q("down"));
            assertThat(List.of(down.start().orElseThrow(), down.minValue().orElseThrow(), down.maxValue().orElseThrow()))
                    .containsExactly(-1L, Long.MIN_VALUE, -1L);
            assertThat(catalog.sequences().get(q("small")).minValue()).contains((long) Short.MIN_VALUE);

            Sequence explicit = catalog.sequences().get(q("explicit"));
            assertThat(List.of(explicit.start().orElseThrow(), explicit.minValue().orElseThrow(), explicit.maxValue().orElseThrow()))
                    .containsExactly(7L, 5L, 50L);
        }

        @Test
        void ownershipCanBeSetAndReleased() {
            Catalog owned = catalog("CREATE TABLE t (a int); CREATE SEQUENCE s; ALTER SEQUENCE s OWNED BY t.a;");
            assertThat(owned.sequences().get(q("s")).ownedBy()).contains(new ColumnRef(q("t"), "a"));

            Catalog released = catalog("CREATE TABLE t (a int); CREATE SEQUENCE s OWNED BY t.a; ALTER SEQUENCE s OWNED BY NONE;");
            assertThat(released.sequences().get(q("s")).ownedBy()).isEmpty();
        }
    }

    @Nested
    class Constraints {

        @Test
        void foreignKeysAreCheckedAndCompleted() {
            Catalog catalog =
                    catalog(
                            """
                            CREATE TABLE p (id int PRIMARY KEY, v int);
                            CREATE UNIQUE INDEX ON p (v);
                            CREATE TABLE c (a int REFERENCES p, b int REFERENCES p (v));
                            """);
            ForeignKey toPrimaryKey = (ForeignKey) table(catalog, "c").constraint("c_a_fkey").orElseThrow();
            assertThat(toPrimaryKey.referencedColumns()).as("filled in from the primary key").containsExactly("id");
            assertThat(table(catalog, "c").constraint("c_b_fkey")).as("a unique index is enough").isPresent();
        }

        @Test
        void aForeignKeyIsBoundToTheEarliestMatchingKey() {
            CatalogResult result =
                    CatalogBuilder.fromSql(
                            """
                            CREATE TABLE p (id int NOT NULL UNIQUE);
                            ALTER TABLE p ADD PRIMARY KEY (id);
                            CREATE TABLE c (p_id int REFERENCES p (id));
                            ALTER TABLE p DROP CONSTRAINT p_id_key;
                            ALTER TABLE p DROP CONSTRAINT p_pkey;
                            """);
            assertThat(result.diagnostics())
                    .extracting(Diagnostic::message)
                    .containsExactly("cannot drop constraint p_id_key on table p because other objects depend on it");
            assertThat(constraintNames(result.catalog(), "p")).containsExactly("p_id_key");
        }

        @Test
        void validatingAConstraintClearsNotValid() {
            Catalog catalog =
                    catalog(
                            """
                            CREATE TABLE t (a int);
                            ALTER TABLE t ADD CONSTRAINT positive CHECK (a > 0) NOT VALID;
                            ALTER TABLE t VALIDATE CONSTRAINT positive;
                            """);
            assertThat(((Check) table(catalog, "t").constraint("positive").orElseThrow()).notValid()).isFalse();
        }

        @Test
        void aUniqueIndexCanBecomeThePrimaryKey() {
            Catalog catalog =
                    catalog("CREATE TABLE t (a int); CREATE UNIQUE INDEX i ON t (a); ALTER TABLE t ADD PRIMARY KEY USING INDEX i;");
            Constraint key = table(catalog, "t").constraint("i").orElseThrow();
            assertThat(key).isInstanceOf(PrimaryKey.class);
            assertThat(column(catalog, "t", "a").notNull()).isTrue();
            assertThat(catalog.indexes()).as("the index now belongs to the constraint").isEmpty();
        }

        @Test
        void constraintsAreListedInNameOrder() {
            Catalog catalog = catalog("CREATE TABLE t (z int UNIQUE, a int UNIQUE, id int PRIMARY KEY)");
            assertThat(constraintNames(catalog, "t")).containsExactly("t_a_key", "t_pkey", "t_z_key");
        }
    }

    @Nested
    class RenamesAndDrops {

        @Test
        void renamingAColumnUpdatesWhateverMentionsIt() {
            Catalog catalog =
                    catalog(
                            """
                            CREATE TABLE t (a int CHECK (a > 0), b int GENERATED ALWAYS AS (a + 1) STORED);
                            CREATE INDEX i ON t ((a * 2)) WHERE a < 9;
                            ALTER TABLE t RENAME COLUMN a TO "Big Name";
                            """);
            assertThat(((Check) table(catalog, "t").constraint("t_a_check").orElseThrow()).expression())
                    .isEqualTo(new Expression("\"Big Name\" > 0"));
            assertThat(column(catalog, "t", "b").generated().orElseThrow().expression())
                    .isEqualTo(new Expression("\"Big Name\" + 1"));
            assertThat(catalog.indexes().get(q("i")).columns().get(0).expression())
                    .contains(new Expression("\"Big Name\" * 2"));
            assertThat(catalog.indexes().get(q("i")).predicate()).contains(new Expression("\"Big Name\" < 9"));
        }

        @Test
        void actionsAfterARenameApplyToTheRenamedTable() {
            Catalog catalog = catalog("CREATE TABLE t (a int); ALTER TABLE t RENAME TO u, ADD COLUMN b int;");
            assertThat(catalog.tables()).containsOnlyKeys(q("u"));
            assertThat(columnNames(catalog, "u")).containsExactly("a", "b");
        }

        @Test
        void droppingAColumnTakesItsOwnTablesDependentsWithIt() {
            Catalog catalog =
                    catalog(
                            """
                            CREATE TABLE t (id int PRIMARY KEY, parent int REFERENCES t, CHECK (parent > 0));
                            CREATE INDEX i ON t (parent);
                            CREATE SEQUENCE s OWNED BY t.parent;
                            ALTER TABLE t DROP COLUMN parent;
                            """);
            assertThat(constraintNames(catalog, "t")).containsExactly("t_pkey");
            assertThat(catalog.indexes()).isEmpty();
            assertThat(catalog.sequences()).isEmpty();
        }

        @Test
        void cascadeRemovesForeignKeysInOtherTables() {
            Catalog catalog =
                    catalog(
                            """
                            CREATE TABLE p (id int PRIMARY KEY, u int UNIQUE);
                            CREATE TABLE c (p_id int REFERENCES p, p_u int REFERENCES p (u));
                            ALTER TABLE p DROP COLUMN u CASCADE;
                            ALTER TABLE p DROP CONSTRAINT p_pkey CASCADE;
                            CREATE TABLE e (v int);
                            CREATE UNIQUE INDEX e_v ON e (v);
                            CREATE TABLE f (x int REFERENCES e (v));
                            DROP INDEX e_v CASCADE;
                            """);
            assertThat(table(catalog, "c").constraints()).isEmpty();
            assertThat(table(catalog, "p").constraints()).isEmpty();
            assertThat(table(catalog, "f").constraints()).isEmpty();
            assertThat(columnNames(catalog, "c")).as("the columns themselves stay").containsExactly("p_id", "p_u");
        }

        @Test
        void tablesThatReferenceEachOtherCanBeDroppedTogether() {
            Catalog catalog =
                    catalog(
                            """
                            CREATE TABLE a (id int PRIMARY KEY);
                            CREATE TABLE b (id int PRIMARY KEY, a_id int REFERENCES a);
                            ALTER TABLE a ADD COLUMN b_id int REFERENCES b;
                            DROP TABLE a, b;
                            """);
            assertThat(catalog).isEqualTo(Catalog.empty());
        }

        @Test
        void unusedObjectsDropCleanly() {
            Catalog catalog =
                    catalog(
                            """
                            CREATE TYPE mood AS ENUM ('a'); DROP TYPE mood;
                            CREATE SEQUENCE s; DROP SEQUENCE s;
                            CREATE TABLE t (id serial PRIMARY KEY, a int); CREATE INDEX i ON t (a); DROP INDEX i; DROP TABLE t;
                            """);
            assertThat(catalog).isEqualTo(Catalog.empty());
        }

        @Test
        void generatedIndexNamesFollowPostgres() {
            Catalog catalog = catalog("CREATE TABLE t (a int); CREATE INDEX ON t ((a + 1), (a + 2), abs(a));");
            assertThat(catalog.indexes()).containsOnlyKeys(q("t_expr_expr1_abs_idx"));
        }
    }

    @Nested
    class Api {

        @Test
        void theSameSchemaWrittenTwoWaysGivesEqualCatalogs() {
            Catalog inline = catalog("CREATE TABLE t (id int PRIMARY KEY, a int UNIQUE)");
            Catalog stepwise =
                    catalog(
                            """
                            CREATE TABLE t (id int NOT NULL, a int);
                            ALTER TABLE t ADD CONSTRAINT t_a_key UNIQUE (a);
                            ALTER TABLE t ADD CONSTRAINT t_pkey PRIMARY KEY (id);
                            """);
            assertThat(inline).isEqualTo(stepwise).hasSameHashCodeAs(stepwise);
        }

        @Test
        void objectsAreListedInNameOrderAndCannotBeChanged() {
            Catalog catalog = catalog("CREATE TABLE z (a int); CREATE TABLE a (a int); CREATE INDEX z_a ON z (a); CREATE INDEX a_a ON a (a);");
            assertThat(catalog.tables().keySet()).containsExactly(q("a"), q("z"));
            assertThat(catalog.indexesOf(q("z"))).extracting(i -> i.name().orElseThrow()).containsExactly("z_a");
            assertThat(catalog.table(q("nope"))).isEmpty();
            assertThatThrownBy(() -> catalog.tables().clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> catalog.indexes().remove(q("z_a"))).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void theDefaultSchemaIsConfigurable() {
            CatalogResult result = CatalogBuilder.fromSql("CREATE TYPE mood AS ENUM ('a'); CREATE TABLE t (m mood);", new ParserOptions("app"));
            Table table = result.catalog().table(new QualifiedName("app", "t")).orElseThrow();
            assertThat(table.column("m").orElseThrow().type().toSql()).isEqualTo("app.mood");
        }

        @Test
        void rejectsNullArguments() {
            assertThatThrownBy(() -> CatalogBuilder.build(null, ParserOptions.defaults())).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> CatalogBuilder.build(List.of(), null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new CatalogResult(null, List.of())).isInstanceOf(NullPointerException.class);
            assertThat(CatalogBuilder.build(List.of(), ParserOptions.defaults()).catalog()).isEqualTo(Catalog.empty());
            assertThat(Optional.of(Catalog.empty().sequences())).contains(java.util.Map.of());
        }
    }
}
