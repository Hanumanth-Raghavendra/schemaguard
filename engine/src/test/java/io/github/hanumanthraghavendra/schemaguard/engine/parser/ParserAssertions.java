package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic;
import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Severity;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/** Shared helpers for the parser tests. */
final class ParserAssertions {

    private ParserAssertions() {}

    /** Parses one statement of the given kind that must produce no diagnostics. */
    static <T extends Statement> T only(String sql, Class<T> type) {
        ParseResult result = Parser.parse(sql);
        assertThat(result.diagnostics()).as("diagnostics for: %s", sql).isEmpty();
        assertThat(result.statements()).hasSize(1);
        assertThat(result.statements().get(0)).isInstanceOf(type);
        return type.cast(result.statements().get(0));
    }

    /** Parses a statement expected to fail with exactly one syntax error; returns its message. */
    static String syntaxError(String sql) {
        ParseResult result = Parser.parse(sql);
        assertThat(result.diagnostics()).as("diagnostics for: %s", sql).hasSize(1);
        Diagnostic diagnostic = result.diagnostics().get(0);
        assertThat(diagnostic.severity()).isEqualTo(Severity.ERROR);
        assertThat(diagnostic.code()).isEqualTo(ParseErrors.SYNTAX_ERROR);
        assertThat(result.statements()).hasSize(1).first().isInstanceOf(Statement.Invalid.class);
        assertThat(result.hasErrors()).isTrue();
        return diagnostic.message();
    }

    /** Parses a statement expected to hit an unsupported feature; returns the warning message. */
    static String unsupportedFeature(String sql) {
        ParseResult result = Parser.parse(sql);
        assertThat(result.diagnostics()).as("diagnostics for: %s", sql).hasSize(1);
        Diagnostic diagnostic = result.diagnostics().get(0);
        assertThat(diagnostic.severity()).isEqualTo(Severity.WARNING);
        assertThat(diagnostic.code()).isEqualTo(ParseErrors.UNSUPPORTED_FEATURE);
        assertThat(result.statements()).hasSize(1).first().isInstanceOf(Statement.Unsupported.class);
        assertThat(result.hasErrors()).isFalse();
        return diagnostic.message();
    }

    /** Turns lines of "input | expected" into test arguments. */
    static Stream<Arguments> rows(String block) {
        return block.lines()
                .filter(line -> !line.isBlank())
                .map(
                        line -> {
                            int bar = line.lastIndexOf('|');
                            return Arguments.of(line.substring(0, bar).strip(), line.substring(bar + 1).strip());
                        });
    }

    /** A one-word summary of each statement, for asserting how a script was classified. */
    static List<String> kinds(ParseResult result) {
        return result.statements().stream()
                .map(
                        s ->
                                switch (s) {
                                    case Statement.CreateTable c -> "table:" + c.table().name().name();
                                    case Statement.AlterTable a -> "alter:" + a.table().name();
                                    case Statement.CreateIndex i -> "index:" + i.index().name().orElse("?");
                                    case Statement.CreateEnum e -> "enum:" + e.type().name().name();
                                    case Statement.AddEnumValue a -> "enum-add:" + a.type().name();
                                    case Statement.RenameEnumValue r -> "enum-rename:" + r.type().name();
                                    case Statement.CreateSequence c -> "sequence:" + c.sequence().name().name();
                                    case Statement.AlterSequenceOwner a -> "sequence-owner:" + a.sequence().name();
                                    case Statement.Drop d -> "drop:" + d.kind();
                                    case Statement.Ignored i -> "ignored:" + i.kind();
                                    case Statement.Unsupported u -> "unsupported:" + u.kind();
                                    case Statement.Invalid i -> "invalid";
                                })
                .toList();
    }
}
