package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.kinds;
import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.only;
import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.rows;
import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.syntaxError;
import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.unsupportedFeature;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.engine.model.EnumType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class EnumParserTest {

    private static EnumType type(String sql) {
        return only(sql, Statement.CreateEnum.class).type();
    }

    @Test
    void createEnumKeepsLabelsInOrder() {
        assertThat(type("CREATE TYPE public.mood AS ENUM ('sad', 'ok', 'happy');"))
                .isEqualTo(new EnumType(new QualifiedName("public", "mood"), List.of("sad", "ok", "happy")));
    }

    @Test
    void labelsAreCaseSensitiveStrings() {
        assertThat(type("CREATE TYPE t AS ENUM ('it''s', 'UPPER', 'upper')").labels())
                .containsExactly("it's", "UPPER", "upper");
    }

    @Test
    void anEnumMayBeEmptyAndItsNameQuoted() {
        EnumType type = type("CREATE TYPE shop.\"Mood\" AS ENUM ()");
        assertThat(type.name()).isEqualTo(new QualifiedName("shop", "Mood"));
        assertThat(type.labels()).isEmpty();
    }

    @Test
    void addValue() {
        Statement.AddEnumValue after =
                only("ALTER TYPE mood ADD VALUE IF NOT EXISTS 'great' AFTER 'happy'", Statement.AddEnumValue.class);
        assertThat(after.type()).isEqualTo(new QualifiedName("public", "mood"));
        assertThat(after.value()).isEqualTo("great");
        assertThat(after.ifNotExists()).isTrue();
        assertThat(after.before()).isEmpty();
        assertThat(after.after()).contains("happy");

        Statement.AddEnumValue before =
                only("ALTER TYPE shop.mood ADD VALUE 'meh' BEFORE 'ok'", Statement.AddEnumValue.class);
        assertThat(before.type()).isEqualTo(new QualifiedName("shop", "mood"));
        assertThat(before.ifNotExists()).isFalse();
        assertThat(before.before()).contains("ok");
        assertThat(before.after()).isEmpty();

        Statement.AddEnumValue last = only("ALTER TYPE mood ADD VALUE 'x'", Statement.AddEnumValue.class);
        assertThat(last.before()).isEqualTo(Optional.empty());
        assertThat(last.after()).isEqualTo(Optional.empty());
    }

    @Test
    void renameValue() {
        Statement.RenameEnumValue rename =
                only("ALTER TYPE mood RENAME VALUE 'ok' TO 'fine'", Statement.RenameEnumValue.class);
        assertThat(rename.from()).isEqualTo("ok");
        assertThat(rename.to()).isEqualTo("fine");
    }

    @Test
    void ownershipChangesAreIgnored() {
        ParseResult result =
                Parser.parse("ALTER TYPE mood OWNER TO app; ALTER TYPE mood ADD VALUE 'x'; ALTER TYPE mood RENAME VALUE 'x' TO 'y';");
        assertThat(result.diagnostics()).isEmpty();
        assertThat(kinds(result)).containsExactly("ignored:ALTER TYPE", "enum-add:mood", "enum-rename:mood");
    }

    static Stream<Arguments> unsupportedCases() {
        return rows(
                """
                CREATE TYPE c AS (a int, b text)          | CREATE TYPE forms other than ENUM
                CREATE TYPE r AS RANGE (subtype = int4)   | CREATE TYPE forms other than ENUM
                CREATE TYPE shell                         | CREATE TYPE forms other than ENUM
                ALTER TYPE mood RENAME TO feeling         | ALTER TYPE forms other than ADD VALUE and RENAME VALUE
                ALTER TYPE mood ADD ATTRIBUTE a int       | ALTER TYPE forms other than ADD VALUE and RENAME VALUE
                """);
    }

    @ParameterizedTest
    @MethodSource("unsupportedCases")
    void otherFormsAreReported(String sql, String feature) {
        assertThat(unsupportedFeature(sql)).isEqualTo("Unsupported feature: " + feature);
    }

    static Stream<Arguments> syntaxErrorCases() {
        return rows(
                """
                CREATE TYPE t AS ENUM ('a', 'a')        | Enum label 'a' is specified more than once
                CREATE TYPE t AS ENUM (a)               | Expected an enum label but found 'a'
                CREATE TYPE t AS ENUM ('a' 'b')         | Expected ',' or ')' but found ''b''
                CREATE TYPE t AS ENUM ('a') extra       | Unexpected 'extra' at the end of the statement
                CREATE TYPE t AS ENUM 'a'               | Expected '(' but found ''a''
                CREATE TYPE                             | Expected a type name but found end of statement
                ALTER TYPE t ADD VALUE                  | Expected an enum label but found end of statement
                ALTER TYPE t ADD VALUE 'a' BEFORE       | Expected an enum label but found end of statement
                ALTER TYPE t ADD VALUE 'a' 'b'          | Unexpected ''b'' at the end of the statement
                ALTER TYPE t RENAME VALUE 'a' 'b'       | Expected TO but found ''b''
                ALTER TYPE t RENAME VALUE 'a' TO 'b' c  | Unexpected 'c' at the end of the statement
                """);
    }

    @ParameterizedTest
    @MethodSource("syntaxErrorCases")
    void reportsAPreciseMessage(String sql, String message) {
        assertThat(syntaxError(sql)).isEqualTo(message);
    }
}
