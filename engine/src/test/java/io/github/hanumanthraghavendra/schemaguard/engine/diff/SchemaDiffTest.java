package io.github.hanumanthraghavendra.schemaguard.engine.diff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogBuilder;
import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogResult;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SchemaDiffTest {

    private static final String COSMETIC = " (cosmetic: differs only in parentheses or casts)";

    private static Catalog catalog(String sql) {
        CatalogResult result = CatalogBuilder.fromSql(sql);
        assertThat(result.diagnostics()).as("diagnostics for: %s", sql).isEmpty();
        return result.catalog();
    }

    private static SchemaDiff compare(String before, String after) {
        return SchemaDiff.compare(catalog(before), catalog(after));
    }

    /** The diff between two scripts, one formatted line per change. */
    private static List<String> diff(String before, String after) {
        return compare(before, after).changes().stream().map(ChangeFormatter::format).toList();
    }

    @Test
    void identicalSchemasHaveNoChanges() {
        String sql = "CREATE TYPE m AS ENUM ('a'); CREATE TABLE t (a int PRIMARY KEY); CREATE INDEX i ON t (a); CREATE SEQUENCE s;";
        SchemaDiff diff = compare(sql, sql);
        assertThat(diff.isEmpty()).isTrue();
        assertThat(diff.significant()).isEmpty();
        assertThat(ChangeFormatter.format(diff)).isEmpty();
    }

    @Nested
    class EnumeratedTypes {

        @Test
        void addedAndDropped() {
            assertThat(diff("", "CREATE TYPE mood AS ENUM ('a', 'it''s')"))
                    .containsExactly("+ enum public.mood ('a', 'it''s')");
            assertThat(diff("CREATE TYPE mood AS ENUM ('a')", "")).containsExactly("- enum public.mood");
        }

        @Test
        void addedValuesSayWhereTheyGo() {
            assertThat(diff("CREATE TYPE m AS ENUM ('a', 'b')", "CREATE TYPE m AS ENUM ('z', 'a', 'x', 'y', 'b', 'c')"))
                    .containsExactly(
                            "~ enum public.m: value 'z' added before 'a'",
                            "~ enum public.m: value 'x' added before 'b'",
                            "~ enum public.m: value 'y' added before 'b'",
                            "~ enum public.m: value 'c' added at the end");
        }

        @Test
        void aValueInTheSamePlaceIsARenameCandidate() {
            assertThat(diff("CREATE TYPE m AS ENUM ('a', 'old', 'b')", "CREATE TYPE m AS ENUM ('a', 'new', 'b')"))
                    .containsExactly(
                            "~ enum public.m: value 'old' removed",
                            "~ enum public.m: value 'new' added before 'b'",
                            "? enum public.m: value 'old' may have been renamed to 'new'"
                                    + " (confidence 0.50: takes the place of the removed value; names 0% similar)");
        }

        @Test
        void unevenGapsAreNotRenameCandidates() {
            assertThat(diff("CREATE TYPE m AS ENUM ('a', 'x', 'y', 'b')", "CREATE TYPE m AS ENUM ('a', 'z', 'b')"))
                    .containsExactly(
                            "~ enum public.m: value 'x' removed",
                            "~ enum public.m: value 'y' removed",
                            "~ enum public.m: value 'z' added before 'b'");
        }

        @Test
        void reorderedValues() {
            assertThat(diff("CREATE TYPE m AS ENUM ('a', 'b', 'c')", "CREATE TYPE m AS ENUM ('b', 'a', 'd')"))
                    .containsExactly(
                            "~ enum public.m: value 'c' removed",
                            "~ enum public.m: value 'd' added at the end",
                            "~ enum public.m: values reordered ('a', 'b', 'c') -> ('b', 'a', 'd')");
        }
    }

    @Nested
    class Tables {

        @Test
        void addedAndDropped() {
            assertThat(diff("", "CREATE TABLE t (a int PRIMARY KEY, b text)"))
                    .containsExactly("+ table public.t (2 columns, 1 constraint)");
            assertThat(diff("CREATE TABLE t (a int)", "")).containsExactly("- table public.t");
        }

        @Test
        void indexesOfADroppedTableAreListedToo() {
            assertThat(diff("CREATE TABLE t (a int); CREATE INDEX i ON t (a);", ""))
                    .containsExactly("- table public.t", "- index public.i ON public.t USING btree (a)");
        }

        @Test
        void tableProperties() {
            assertThat(diff("CREATE UNLOGGED TABLE t (a int)", "CREATE TABLE t (a int)"))
                    .containsExactly("~ table public.t: unlogged -> permanent");
            assertThat(diff("CREATE TABLE t (a int)", "CREATE TABLE t (a int) PARTITION BY RANGE (a)"))
                    .containsExactly("~ table public.t: partitioning none -> range (a)");
            assertThat(diff("CREATE TABLE p (x int); CREATE TABLE t (a int)", "CREATE TABLE p (x int); CREATE TABLE t (a int) INHERITS (p)"))
                    .containsExactly("~ table public.t: inherits none -> (public.p)");
        }

        @Test
        void sameColumnsUnderANewNameIsARenameCandidate() {
            assertThat(diff("CREATE TABLE orders (id int, total numeric)", "CREATE TABLE orders_archive (id int, total numeric)"))
                    .containsExactly(
                            "- table public.orders",
                            "+ table public.orders_archive (2 columns, 0 constraints)",
                            "? table public.orders may have been renamed to public.orders_archive"
                                    + " (confidence 0.90: same 2 columns; one name contains the other)");
            assertThat(diff("CREATE TABLE t (a int)", "CREATE TABLE archive.t (a int)"))
                    .containsExactly(
                            "+ table archive.t (1 column, 0 constraints)",
                            "- table public.t",
                            "? table public.t may have been renamed to archive.t"
                                    + " (confidence 1.00: same column; same name; moved from schema public to archive)");
        }

        @Test
        void differentOrEmptyTablesAreNotRenameCandidates() {
            assertThat(diff("CREATE TABLE a (x int)", "CREATE TABLE b (x bigint)")).hasSize(2);
            assertThat(diff("CREATE TABLE a ()", "CREATE TABLE b ()")).hasSize(2);
        }
    }

    @Nested
    class Columns {

        @Test
        void eachAttributeIsReportedSeparately() {
            assertThat(diff("CREATE TABLE t (a int, b text, c int)", "CREATE TABLE t (a bigint NOT NULL DEFAULT 1, b text COLLATE \"C\", d int)"))
                    .containsExactly(
                            "~ column public.t.a: type integer -> bigint",
                            "~ column public.t.a: NOT NULL added",
                            "~ column public.t.a: default none -> 1",
                            "~ column public.t.b: collation default -> \"C\"",
                            "+ column public.t.d integer",
                            "- column public.t.c integer");
        }

        @Test
        void identityAndGeneratedColumns() {
            assertThat(
                            diff(
                                    "CREATE TABLE t (a int NOT NULL DEFAULT 1, b int, g int GENERATED ALWAYS AS (b * 2) STORED)",
                                    "CREATE TABLE t (a int GENERATED ALWAYS AS IDENTITY, b int, g int GENERATED ALWAYS AS (b * 3) STORED)"))
                    .containsExactly(
                            "~ column public.t.a: default 1 -> none",
                            "~ column public.t.a: identity none -> always",
                            "~ column public.t.g: generated (b * 2) stored -> (b * 3) stored");
        }

        @Test
        void differencesOnlyInPrintingAreCosmetic() {
            SchemaDiff diff =
                    compare(
                            "CREATE TABLE t (a numeric DEFAULT 0, b int, g int GENERATED ALWAYS AS (b * 2) STORED)",
                            "CREATE TABLE t (a numeric DEFAULT (0)::numeric, b int, g int GENERATED ALWAYS AS ((b * (2)::integer)) STORED)");
            assertThat(diff.changes().stream().map(ChangeFormatter::format))
                    .containsExactly(
                            "~ column public.t.a: default 0 -> (0)::numeric" + COSMETIC,
                            "~ column public.t.g: generated (b * 2) stored -> (b * (2)::integer) stored" + COSMETIC);
            assertThat(diff.changes()).allMatch(Change::cosmetic);
            assertThat(diff.significant()).isEmpty();
            assertThat(diff.isEmpty()).isFalse();
        }

        @Test
        void columnOrder() {
            assertThat(diff("CREATE TABLE t (a int, b int)", "CREATE TABLE t (b int, a int)"))
                    .containsExactly("~ table public.t: column order (a, b) -> (b, a)");
        }

        @Test
        void renameCandidates() {
            assertThat(diff("CREATE TABLE t (id int, email text)", "CREATE TABLE t (id int, email_address text)"))
                    .containsExactly(
                            "+ column public.t.email_address text",
                            "- column public.t.email text",
                            "? column public.t.email may have been renamed to email_address"
                                    + " (confidence 0.86: same type and attributes; one name contains the other; both are column 2)");
            assertThat(diff("CREATE TABLE t (id int, qty int)", "CREATE TABLE t (id int, quantity int)"))
                    .contains(
                            "? column public.t.qty may have been renamed to quantity"
                                    + " (confidence 0.72: same type and attributes; one name abbreviates the other; both are column 2)");
        }

        @Test
        void eachColumnIsUsedInAtMostOneCandidate() {
            assertThat(diff("CREATE TABLE t (first_name text, last_name text)", "CREATE TABLE t (name text)"))
                    .containsExactly(
                            "+ column public.t.name text",
                            "- column public.t.first_name text",
                            "- column public.t.last_name text",
                            "? column public.t.first_name may have been renamed to name"
                                    + " (confidence 0.86: same type and attributes; one name contains the other; both are column 1)");
        }

        @Test
        void weakOrMismatchedCandidatesAreNotReported() {
            assertThat(diff("CREATE TABLE t (id int, x int)", "CREATE TABLE t (id int, y int)"))
                    .as("same type and place, but nothing else in common")
                    .hasSize(2);
            assertThat(diff("CREATE TABLE t (email text)", "CREATE TABLE t (email_address varchar(100))"))
                    .as("different type")
                    .hasSize(2);
            assertThat(diff("CREATE TABLE t (qty int, note text)", "CREATE TABLE t (note text, quantity int)"))
                    .as("abbreviation alone, in another place")
                    .hasSize(2);
        }
    }

    @Nested
    class Constraints {

        @Test
        void changedRenamedDroppedAndAdded() {
            assertThat(
                            diff(
                                    "CREATE TABLE t (a int, b int, CONSTRAINT c1 CHECK (a > 0), CONSTRAINT c2 CHECK (b > 0), CONSTRAINT c3 CHECK (a < 100))",
                                    "CREATE TABLE t (a int, b int, CONSTRAINT c1 CHECK (a >= 0), CONSTRAINT b_positive CHECK (b > 0), CONSTRAINT c4 CHECK (b < 9))"))
                    .containsExactly(
                            "~ constraint public.t.c1: CHECK (a > 0) -> CHECK (a >= 0)",
                            "~ constraint public.t.c2: renamed to b_positive",
                            "- constraint public.t.c3 CHECK (a < 100)",
                            "+ constraint public.t.c4 CHECK (b < 9)");
        }

        @Test
        void validation() {
            String notValid = "CREATE TABLE t (a int); ALTER TABLE t ADD CONSTRAINT v CHECK (a > 0) NOT VALID;";
            String valid = "CREATE TABLE t (a int, CONSTRAINT v CHECK (a > 0));";
            assertThat(diff(notValid, valid)).containsExactly("~ constraint public.t.v: validated");
            assertThat(diff(valid, notValid)).containsExactly("~ constraint public.t.v: CHECK (a > 0) -> CHECK (a > 0) NOT VALID");
            assertThat(compare(valid, notValid).significant()).hasSize(1);
        }

        @Test
        void validatedWhileItsTextChanged() {
            assertThat(
                            diff(
                                    "CREATE TABLE t (a int); ALTER TABLE t ADD CONSTRAINT v CHECK (a > 0) NOT VALID;",
                                    "CREATE TABLE t (a int, CONSTRAINT v CHECK ((a > (0)::integer)))"))
                    .containsExactly(
                            "~ constraint public.t.v: validated",
                            "~ constraint public.t.v: CHECK (a > 0) NOT VALID -> CHECK (a > (0)::integer)" + COSMETIC);
        }

        @Test
        void renamedAndValidated() {
            assertThat(
                            diff(
                                    "CREATE TABLE t (a int); ALTER TABLE t ADD CONSTRAINT x CHECK (a > 0) NOT VALID;",
                                    "CREATE TABLE t (a int, CONSTRAINT y CHECK (a > 0))"))
                    .containsExactly("~ constraint public.t.x: renamed to y", "~ constraint public.t.y: validated");
        }

        @Test
        void cosmeticDifferences() {
            assertThat(diff("CREATE TABLE t (a int, CONSTRAINT c CHECK (a > 0))", "CREATE TABLE t (a int, CONSTRAINT c CHECK ((a > (0)::integer)))"))
                    .containsExactly("~ constraint public.t.c: CHECK (a > 0) -> CHECK (a > (0)::integer)" + COSMETIC);
            assertThat(diff("CREATE TABLE t (a int, CONSTRAINT x CHECK (a > 0))", "CREATE TABLE t (a int, CONSTRAINT y CHECK ((a > (0)::integer)))"))
                    .containsExactly(
                            "~ constraint public.t.x: renamed to y",
                            "~ constraint public.t.y: CHECK (a > 0) -> CHECK (a > (0)::integer)" + COSMETIC);
        }

        @Test
        void keysAndForeignKeys() {
            assertThat(
                            diff(
                                    "CREATE TABLE p (id int PRIMARY KEY); CREATE TABLE t (a int REFERENCES p, b int)",
                                    "CREATE TABLE p (id int PRIMARY KEY); CREATE TABLE t (a int REFERENCES p ON DELETE CASCADE, b int UNIQUE)"))
                    .containsExactly(
                            "~ constraint public.t.t_a_fkey: FOREIGN KEY (a) REFERENCES public.p (id)"
                                    + " -> FOREIGN KEY (a) REFERENCES public.p (id) ON DELETE CASCADE",
                            "+ constraint public.t.t_b_key UNIQUE (b)");
        }
    }

    @Nested
    class Indexes {

        @Test
        void changedRenamedDroppedAndAdded() {
            assertThat(
                            diff(
                                    "CREATE TABLE t (a int, b int); CREATE INDEX i1 ON t (a); CREATE INDEX i2 ON t (b); CREATE INDEX i3 ON t (a, b);",
                                    "CREATE TABLE t (a int, b int); CREATE INDEX i1 ON t (a DESC); CREATE INDEX i2_renamed ON t (b);"
                                            + " CREATE UNIQUE INDEX i4 ON t (b) WHERE a > 0;"))
                    .containsExactly(
                            "~ index public.i1: ON public.t USING btree (a) -> ON public.t USING btree (a DESC)",
                            "~ index public.i2: renamed to i2_renamed",
                            "- index public.i3 ON public.t USING btree (a, b)",
                            "+ unique index public.i4 ON public.t USING btree (b) WHERE (a > 0)");
        }

        @Test
        void renamedWhileItsTextChanged() {
            assertThat(diff("CREATE TABLE t (a text); CREATE INDEX x ON t (lower(a));", "CREATE TABLE t (a text); CREATE INDEX y ON t (lower((a)::text));"))
                    .containsExactly(
                            "~ index public.x: renamed to y",
                            "~ index public.y: ON public.t USING btree ((lower(a))) -> ON public.t USING btree ((lower((a)::text)))" + COSMETIC);
        }

        @Test
        void becomingUniqueIsAChange() {
            assertThat(diff("CREATE TABLE t (a int); CREATE INDEX i ON t (a);", "CREATE TABLE t (a int); CREATE UNIQUE INDEX i ON t (a);"))
                    .containsExactly("~ index public.i: ON public.t USING btree (a) -> UNIQUE ON public.t USING btree (a)");
        }

        @Test
        void aNameReusedOnAnotherTableIsANewIndex() {
            assertThat(
                            diff(
                                    "CREATE TABLE a (x int); CREATE TABLE b (x int); CREATE INDEX i ON a (x);",
                                    "CREATE TABLE a (x int); CREATE TABLE b (x int); CREATE INDEX i ON b (x);"))
                    .containsExactly(
                            "- index public.i ON public.a USING btree (x)", "+ index public.i ON public.b USING btree (x)");
        }
    }

    @Nested
    class Sequences {

        @Test
        void addedChangedAndDropped() {
            assertThat(diff("", "CREATE SEQUENCE s"))
                    .containsExactly("+ sequence public.s AS bigint START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 CACHE 1");
            assertThat(diff("CREATE SEQUENCE s", "CREATE SEQUENCE s INCREMENT 5 MAXVALUE 100 CYCLE"))
                    .containsExactly("~ sequence public.s: increment 1 -> 5, maxvalue 9223372036854775807 -> 100, cycle no -> yes");
            assertThat(diff("CREATE SEQUENCE s", "")).containsExactly("- sequence public.s");
        }

        @Test
        void everyPropertyIsNamed() {
            assertThat(
                            diff(
                                    "CREATE TABLE t (a int); CREATE SEQUENCE s OWNED BY t.a",
                                    "CREATE TABLE t (a int); CREATE UNLOGGED SEQUENCE s AS integer START 5 MINVALUE 2 CACHE 10"))
                    .containsExactly(
                            "~ sequence public.s: persistence permanent -> unlogged, type bigint -> integer, start 1 -> 5,"
                                    + " minvalue 1 -> 2, maxvalue 9223372036854775807 -> 2147483647, cache 1 -> 10,"
                                    + " owned by public.t.a -> none");
        }
    }

    @Nested
    class Results {

        @Test
        void sectionsComeInAFixedOrder() {
            List<String> lines =
                    diff(
                            "CREATE SEQUENCE s; CREATE TABLE old_t (a int); CREATE TYPE e AS ENUM ('a');",
                            "CREATE SEQUENCE s2; CREATE TABLE new_t (a int); CREATE TYPE e2 AS ENUM ('a');");
            assertThat(lines)
                    .containsExactly(
                            "- enum public.e",
                            "+ enum public.e2 ('a')",
                            "+ table public.new_t (1 column, 0 constraints)",
                            "- table public.old_t",
                            "? table public.old_t may have been renamed to public.new_t (confidence 0.70: same column; names 40% similar)",
                            "- sequence public.s",
                            "+ sequence public.s2 AS bigint START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 CACHE 1");
        }

        @Test
        void theResultKeepsBothCatalogsAndCannotBeChanged() {
            Catalog before = catalog("CREATE TABLE t (a int)");
            Catalog after = catalog("CREATE TABLE t (a bigint)");
            SchemaDiff diff = SchemaDiff.compare(before, after);
            assertThat(diff.before()).isSameAs(before);
            assertThat(diff.after()).isSameAs(after);
            assertThat(diff).isEqualTo(SchemaDiff.compare(before, after));
            assertThatThrownBy(() -> diff.changes().clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> SchemaDiff.compare(null, after)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> SchemaDiff.compare(before, null)).isInstanceOf(NullPointerException.class);
        }
    }
}
