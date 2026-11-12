package io.github.hanumanthraghavendra.schemaguard.engine.lexer;

import io.github.hanumanthraghavendra.schemaguard.engine.source.SourceSpan;
import java.util.Objects;

/**
 * One lexical unit.
 *
 * @param text  the exact source characters (empty for {@link TokenType#COPY_DATA} and
 *              {@link TokenType#EOF}, whose content is never needed)
 * @param value the normalized form: case-folded for {@link TokenType#WORD}, unescaped
 *              content for strings and quoted identifiers, same as {@code text} otherwise
 */
public record Token(TokenType type, String text, String value, SourceSpan span) {

    public Token {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(span, "span");
    }

    /** True if this is an unquoted word equal to {@code keyword}, which must be lower case. */
    public boolean isWord(String keyword) {
        return type == TokenType.WORD && value.equals(keyword);
    }

    /** True if this is exactly the operator {@code operator}. */
    public boolean isOperator(String operator) {
        return type == TokenType.OPERATOR && value.equals(operator);
    }
}
