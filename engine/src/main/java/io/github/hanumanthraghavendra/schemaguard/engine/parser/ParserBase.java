package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import io.github.hanumanthraghavendra.schemaguard.engine.lexer.Token;
import io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Expression;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Identifiers;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.source.SourceSpan;
import java.util.ArrayList;
import java.util.List;

/**
 * What every statement parser needs: the token cursor, the options, and the small grammar
 * fragments that appear in many statements (names, column lists, expressions, IF EXISTS).
 *
 * <p>All parsers working on one statement share a single {@link TokenCursor}, so one can hand
 * over to another mid-statement.
 */
abstract class ParserBase {

    final TokenCursor cursor;
    final ParserOptions options;

    /** The span of the whole statement, for errors that concern all of it. */
    final SourceSpan span;

    ParserBase(TokenCursor cursor, ParserOptions options, SourceSpan span) {
        this.cursor = cursor;
        this.options = options;
        this.span = span;
    }

    /** Creates a parser that continues from where {@code shared} currently is. */
    ParserBase(ParserBase shared) {
        this(shared.cursor, shared.options, shared.span);
    }

    // ---------------------------------------------------------------- shared pieces

    Expression parenthesizedExpression() {
        Token open = cursor.peek();
        List<Token> inner = cursor.balancedParentheses();
        if (inner.isEmpty()) {
            throw ParseError.syntax("Expected an expression inside the parentheses", open);
        }
        return ExpressionRenderer.toExpression(inner);
    }

    List<String> columnList() {
        cursor.expect(TokenType.LPAREN, "'('");
        List<String> columns = new ArrayList<>();
        do {
            columns.add(cursor.identifier("a column name"));
        } while (cursor.accept(TokenType.COMMA));
        cursor.expect(TokenType.RPAREN, "',' or ')'");
        return columns;
    }

    /** Parses {@code name}, {@code schema.name} or {@code database.schema.name}. */
    QualifiedName qualifiedName(String description) {
        Token start = cursor.peek();
        List<String> parts = new ArrayList<>();
        parts.add(cursor.identifier(description));
        while (cursor.accept(TokenType.DOT)) {
            parts.add(cursor.identifier(description));
        }
        if (parts.size() > 3) {
            throw ParseError.syntax("A name can have at most three parts", start);
        }
        String schema = parts.size() == 1 ? options.defaultSchema() : parts.get(parts.size() - 2);
        return new QualifiedName(schema, parts.get(parts.size() - 1));
    }

    /** Parses a possibly qualified name and returns it as canonical SQL text. */
    String dottedName(String description) {
        StringBuilder name = new StringBuilder(Identifiers.quoteIfNeeded(cursor.identifier(description)));
        while (cursor.accept(TokenType.DOT)) {
            name.append('.').append(Identifiers.quoteIfNeeded(cursor.identifier(description)));
        }
        return name.toString();
    }

    /** Reads an expression that runs to the next top-level comma or the end of the statement. */
    Expression expressionUntilComma(String description) {
        int from = cursor.position();
        int depth = 0;
        while (!cursor.atEnd()) {
            TokenType type = cursor.peek().type();
            if (type == TokenType.COMMA && depth == 0) {
                break;
            }
            if (type == TokenType.LPAREN || type == TokenType.LBRACKET) {
                depth++;
            } else if (type == TokenType.RPAREN || type == TokenType.RBRACKET) {
                depth--;
            }
            cursor.next();
        }
        if (cursor.position() == from) {
            throw cursor.expected(description);
        }
        return ExpressionRenderer.toExpression(cursor.slice(from, cursor.position()));
    }

    /** Reads everything up to the end of the statement as one expression. */
    Expression remainingExpression(String description) {
        int from = cursor.position();
        while (!cursor.atEnd()) {
            cursor.next();
        }
        if (cursor.position() == from) {
            throw cursor.expected(description);
        }
        return ExpressionRenderer.toExpression(cursor.slice(from, cursor.position()));
    }

    /** Reads an integer with an optional sign. The lexer delivers the sign as its own token. */
    long signedInteger() {
        Token start = cursor.peek();
        boolean negative = cursor.peek().isOperator("-");
        if (negative || cursor.peek().isOperator("+")) {
            cursor.next();
        }
        Token number = cursor.expect(TokenType.NUMBER, "an integer");
        try {
            return Long.parseLong((negative ? "-" : "") + number.text().replace("_", ""));
        } catch (NumberFormatException e) {
            throw ParseError.syntax("Expected an integer but found '" + number.text() + "'", start);
        }
    }

    String stringLiteral(String description) {
        return cursor.expect(TokenType.STRING, description).value();
    }

    boolean ifExists() {
        return cursor.acceptWords("if", "exists");
    }

    boolean ifNotExists() {
        return cursor.acceptWords("if", "not", "exists");
    }

    /** Consumes an optional CASCADE or RESTRICT and reports whether it was CASCADE. */
    boolean cascade() {
        if (cursor.acceptWord("cascade")) {
            return true;
        }
        cursor.acceptWord("restrict");
        return false;
    }

    void expectEndOfStatement() {
        if (!cursor.atEnd()) {
            throw ParseError.syntax(
                    "Unexpected " + TokenCursor.describe(cursor.peek()) + " at the end of the statement",
                    cursor.peek());
        }
    }
}
