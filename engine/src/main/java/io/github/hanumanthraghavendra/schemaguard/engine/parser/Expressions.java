package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import io.github.hanumanthraghavendra.schemaguard.engine.lexer.Lexer;
import io.github.hanumanthraghavendra.schemaguard.engine.lexer.Token;
import io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Expression;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Identifiers;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Questions the engine asks about an {@link Expression} without evaluating it. Expressions are
 * stored as canonical text, so each answer comes from re-reading the tokens.
 *
 * <p>These are deliberately simple token scans, not a full SQL expression parser. Where that
 * makes an answer approximate, the method says so.
 */
public final class Expressions {

    private Expressions() {}

    /**
     * The names from {@code columns} that the expression mentions as column references, in
     * order of first appearance. A name directly followed by {@code (} is a function call and a
     * name inside a cast's type is a type, so neither counts.
     */
    public static Set<String> referencedColumns(Expression expression, Set<String> columns) {
        List<Token> tokens = tokens(expression);
        Set<String> found = new LinkedHashSet<>();
        for (int position : columnReferences(tokens, columns)) {
            found.add(tokens.get(position).value());
        }
        return found;
    }

    /** The expression with every reference to column {@code from} changed to {@code to}. */
    public static Expression renameColumn(Expression expression, String from, String to) {
        List<Token> tokens = new ArrayList<>(tokens(expression));
        List<Integer> positions = columnReferences(tokens, Set.of(from));
        if (positions.isEmpty()) {
            return expression;
        }
        for (int position : positions) {
            Token old = tokens.get(position);
            tokens.set(position, new Token(TokenType.QUOTED_IDENTIFIER, to, to, old.span()));
        }
        return new Expression(ExpressionRenderer.render(tokens));
    }

    /**
     * A reduced form of the expression with all parentheses and type casts removed.
     *
     * <p>PostgreSQL prints stored expressions with extra parentheses and explicit casts:
     * {@code total >= 0} comes back as {@code (total >= (0)::numeric)}. Two expressions with the
     * same fingerprint differ at most in those, so a difference between them is probably
     * cosmetic. It is a hint, not a proof: removing parentheses can also hide a real change in
     * evaluation order.
     */
    public static String fingerprint(Expression expression) {
        List<Token> tokens = tokens(expression);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            TokenType type = token.type();
            if (type == TokenType.LPAREN || type == TokenType.RPAREN) {
                continue;
            }
            if (type == TokenType.DOUBLE_COLON) {
                int afterType = skipType(tokens, i + 1);
                if (afterType > i + 1) {
                    i = afterType - 1;
                    continue;
                }
            }
            out.append(out.isEmpty() ? "" : " ").append(ExpressionRenderer.render(List.of(token)));
        }
        return out.toString();
    }

    /** The column name, if the expression is nothing but one column reference. */
    public static Optional<String> asColumn(Expression expression) {
        List<Token> tokens = tokens(expression);
        if (tokens.size() != 1 || !isName(tokens.get(0))) {
            return Optional.empty();
        }
        return Optional.of(tokens.get(0).value());
    }

    /**
     * The name PostgreSQL gives an expression when it builds an index name from it: the
     * function name for a function call, the column name for a cast of a column, {@code case}
     * for a CASE expression, and {@code expr} for anything else.
     */
    public static String indexColumnName(Expression expression) {
        List<Token> tokens = tokens(expression);
        for (int cast = trailingCast(tokens); cast >= 0; cast = trailingCast(tokens)) {
            tokens = ExpressionRenderer.unwrap(tokens.subList(0, cast));
        }
        if (tokens.isEmpty()) {
            return "expr";
        }
        if (tokens.get(0).isWord("case")) {
            return "case";
        }
        if (tokens.size() == 1 && isName(tokens.get(0))) {
            return tokens.get(0).value();
        }
        int i = 0;
        String name = null;
        while (i < tokens.size() && isName(tokens.get(i))) {
            name = tokens.get(i).value();
            i++;
            if (i < tokens.size() && tokens.get(i).type() == TokenType.DOT) {
                i++;
            } else {
                break;
            }
        }
        boolean isCall =
                name != null
                        && i < tokens.size()
                        && tokens.get(i).type() == TokenType.LPAREN
                        && matchingParenthesis(tokens, i) == tokens.size() - 1;
        return isCall ? name : "expr";
    }

    /**
     * True if the expression must be wrapped in parentheses to be used as a column DEFAULT.
     *
     * <p>A DEFAULT is a restricted expression ("b_expr"): it cannot contain bare keywords such
     * as {@code AND}, {@code IS} or {@code AT TIME ZONE}, because the parser would read them as
     * the start of the next column constraint. A function call, a literal, a cast or an
     * arithmetic expression needs no parentheses; anything with a top-level keyword does.
     */
    public static boolean needsParenthesesAsDefault(Expression expression) {
        List<Token> tokens = tokens(expression);
        int depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            TokenType type = tokens.get(i).type();
            if (type == TokenType.LPAREN || type == TokenType.LBRACKET) {
                depth++;
            } else if (type == TokenType.RPAREN || type == TokenType.RBRACKET) {
                depth--;
            } else if (type == TokenType.DOUBLE_COLON) {
                i = Math.max(i, skipType(tokens, i + 1) - 1);
            } else if (type == TokenType.WORD && depth == 0 && i > 0) {
                TokenType previous = tokens.get(i - 1).type();
                TokenType next = i + 1 < tokens.size() ? tokens.get(i + 1).type() : TokenType.EOF;
                boolean partOfName = previous == TokenType.DOT || next == TokenType.DOT || next == TokenType.LPAREN;
                if (!partOfName) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- helpers

    private static List<Token> tokens(Expression expression) {
        List<Token> all = Lexer.tokenize(expression.sql()).tokens();
        return all.subList(0, all.size() - 1);
    }

    /** An identifier that can name something: quoted, or unquoted and not a reserved keyword. */
    private static boolean isName(Token token) {
        return token.type() == TokenType.QUOTED_IDENTIFIER
                || (token.type() == TokenType.WORD && !Identifiers.isReserved(token.value()));
    }

    private static List<Integer> columnReferences(List<Token> tokens, Set<String> columns) {
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.type() == TokenType.DOUBLE_COLON) {
                i = Math.max(i, skipType(tokens, i + 1) - 1);
                continue;
            }
            if (!isName(token) || !columns.contains(token.value())) {
                continue;
            }
            TokenType next = i + 1 < tokens.size() ? tokens.get(i + 1).type() : TokenType.EOF;
            if (next != TokenType.LPAREN && next != TokenType.DOT) {
                positions.add(i);
            }
        }
        return positions;
    }

    /** The index just past the type name that starts at {@code from}, or {@code from} if none does. */
    private static int skipType(List<Token> tokens, int from) {
        if (from >= tokens.size()) {
            return from;
        }
        TokenCursor cursor = new TokenCursor(tokens.subList(from, tokens.size()));
        try {
            TypeParser.parse(cursor);
        } catch (ParseError notAType) {
            return from;
        }
        return from + cursor.position();
    }

    /** The position of a top-level {@code ::} whose type runs to the end, or -1. */
    private static int trailingCast(List<Token> tokens) {
        int depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            TokenType type = tokens.get(i).type();
            if (type == TokenType.LPAREN || type == TokenType.LBRACKET) {
                depth++;
            } else if (type == TokenType.RPAREN || type == TokenType.RBRACKET) {
                depth--;
            } else if (type == TokenType.DOUBLE_COLON
                    && depth == 0
                    && i > 0
                    && skipType(tokens, i + 1) == tokens.size()) {
                return i;
            }
        }
        return -1;
    }

    private static int matchingParenthesis(List<Token> tokens, int open) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            TokenType type = tokens.get(i).type();
            if (type == TokenType.LPAREN) {
                depth++;
            } else if (type == TokenType.RPAREN && --depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
