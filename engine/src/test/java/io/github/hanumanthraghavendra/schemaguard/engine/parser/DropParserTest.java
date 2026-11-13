package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.kinds;
import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.only;
import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.rows;
import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.syntaxError;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ObjectKind;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DropParserTest {

    private static Statement.Drop drop(String sql) {
        return only(sql, Statement.Drop.class);
    }

    @Test
    void dropSeveralTables() {
        Statement.Drop drop = drop("DROP TABLE IF EXISTS a, shop.b CASCADE;");
        assertThat(drop.kind()).isEqualTo(ObjectKind.TABLE);
        assertThat(drop.names()).containsExactly(new QualifiedName("public", "a"), new QualifiedName("shop", "b"));
        assertThat(drop.ifExists()).isTrue();
        assertThat(drop.cascade()).isTrue();
        assertThat(drop.concurrently()).isFalse();
    }

    @Test
    void dropIndexConcurrently() {
        Statement.Drop drop = drop("DROP INDEX CONCURRENTLY IF EXISTS idx");
        assertThat(drop.kind()).isEqualTo(ObjectKind.INDEX);
        assertThat(drop.concurrently()).isTrue();
        assertThat(drop.ifExists()).isTrue();
        assertThat(drop("DROP INDEX idx").concurrently()).isFalse();
    }

    @Test
    void dropTypeAndSequence() {
        Statement.Drop type = drop("DROP TYPE mood RESTRICT");
        assertThat(type.kind()).isEqualTo(ObjectKind.TYPE);
        assertThat(type.cascade()).isFalse();
        assertThat(type.ifExists()).isFalse();
        assertThat(drop("DROP SEQUENCE s").kind()).isEqualTo(ObjectKind.SEQUENCE);
    }

    @Test
    void otherObjectKindsAreReportedAsUnsupported() {
        ParseResult result = Parser.parse("DROP VIEW v; DROP FUNCTION f(int); DROP TABLE t;");
        assertThat(kinds(result)).containsExactly("unsupported:DROP VIEW", "unsupported:DROP FUNCTION", "drop:TABLE");
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsOnly(ParseErrors.UNSUPPORTED_STATEMENT);
    }

    static Stream<Arguments> syntaxErrorCases() {
        return rows(
                """
                DROP TABLE                  | Expected an object name but found end of statement
                DROP TABLE a,               | Expected an object name but found end of statement
                DROP INDEX CONCURRENTLY     | Expected an object name but found end of statement
                DROP TABLE a b              | Unexpected 'b' at the end of the statement
                DROP TABLE a CASCADE extra  | Unexpected 'extra' at the end of the statement
                DROP TABLE a.b.c.d          | A name can have at most three parts
                """);
    }

    @ParameterizedTest
    @MethodSource("syntaxErrorCases")
    void reportsAPreciseMessage(String sql, String message) {
        assertThat(syntaxError(sql)).isEqualTo(message);
    }
}
