package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import io.github.hanumanthraghavendra.schemaguard.engine.lexer.Token;
import io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Expression;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Identifiers;
import java.util.List;
import java.util.Set;

/**
 * Prints tokens back as canonical SQL text: one spacing style, unquoted words in lower case,
 * identifiers quoted only where required, and every string as a standard {@code '...'} literal.
 * Two expressions that differ only in layout, letter case or quoting style render identically.
 */
final class ExpressionRenderer {

    /** Keywords that are followed by a space before {@code (}, unlike function names. */
    private static final Set<String> SPACED_KEYWORDS =
            Set.of(
                    "and", "or", "not", "in", "any", "all", "some", "exists", "case", "when",
                    "then", "else", "as", "is", "like", "ilike", "between", "values", "over",
                    "filter", "from", "where", "select", "on", "using");

    private ExpressionRenderer() {}

    /** Renders an expression, first removing parentheses that wrap the whole of it. */
    static Expression toExpression(List<Token> tokens) {
        return new Expression(render(unwrap(tokens)));
    }

    /** Removes every pair of parentheses that wraps the whole token list. */
    static List<Token> unwrap(List<Token> tokens) {
        List<Token> core = tokens;
        while (isWrappedInParentheses(core)) {
            core = core.subList(1, core.size() - 1);
        }
        return core;
    }

    static String render(List<Token> tokens) {
        StringBuilder out = new StringBuilder();
        Token previous = null;
        boolean previousIsSign = false;
        for (Token token : tokens) {
            if (previous != null && !previousIsSign && needsSpace(previous, token)) {
                out.append(' ');
            }
            out.append(text(token));
            previousIsSign = isSign(token) && startsOperand(previous);
            previous = token;
        }
        return out.toString();
    }

    /** True if the first token is a '(' whose matching ')' is the last token. */
    private static boolean isWrappedInParentheses(List<Token> tokens) {
        if (tokens.size() < 3
                || tokens.get(0).type() != TokenType.LPAREN
                || tokens.get(tokens.size() - 1).type() != TokenType.RPAREN) {
            return false;
        }
        int depth = 0;
        for (int i = 0; i < tokens.size() - 1; i++) {
            TokenType type = tokens.get(i).type();
            if (type == TokenType.LPAREN) {
                depth++;
            } else if (type == TokenType.RPAREN && --depth == 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean needsSpace(Token previous, Token token) {
        TokenType before = previous.type();
        boolean tightAfter =
                before == TokenType.LPAREN
                        || before == TokenType.LBRACKET
                        || before == TokenType.DOT
                        || before == TokenType.DOUBLE_COLON;
        return switch (token.type()) {
            case RPAREN, RBRACKET, COMMA, DOT, DOUBLE_COLON -> false;
            case LPAREN -> !tightAfter && !isName(previous);
            case LBRACKET ->
                    !tightAfter
                            && !isName(previous)
                            && before != TokenType.RBRACKET
                            && before != TokenType.RPAREN
                            && !previous.isWord("array");
            default -> !tightAfter;
        };
    }

    /** A function, type or column name, as opposed to a keyword such as AND or IN. */
    private static boolean isName(Token token) {
        return token.type() == TokenType.QUOTED_IDENTIFIER
                || (token.type() == TokenType.WORD && !SPACED_KEYWORDS.contains(token.value()));
    }

    private static boolean isSign(Token token) {
        return token.isOperator("-") || token.isOperator("+");
    }

    /** True if a sign that follows {@code previous} is a unary sign, not a binary operator. */
    private static boolean startsOperand(Token previous) {
        if (previous == null) {
            return true;
        }
        return switch (previous.type()) {
            case LPAREN, LBRACKET, COMMA, OPERATOR -> true;
            case WORD -> SPACED_KEYWORDS.contains(previous.value());
            default -> false;
        };
    }

    private static String text(Token token) {
        return switch (token.type()) {
            case WORD -> token.value();
            case QUOTED_IDENTIFIER -> Identifiers.quoteIfNeeded(token.value());
            case STRING -> "'" + token.value().replace("'", "''") + "'";
            default -> token.text();
        };
    }
}
