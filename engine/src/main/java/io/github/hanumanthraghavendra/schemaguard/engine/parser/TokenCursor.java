package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import io.github.hanumanthraghavendra.schemaguard.engine.lexer.Token;
import io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType;
import io.github.hanumanthraghavendra.schemaguard.engine.source.SourceSpan;
import java.util.ArrayList;
import java.util.List;

/** A read position over the tokens of one statement, with the lookahead helpers a parser needs. */
final class TokenCursor {

    private final List<Token> tokens;
    private final Token end;
    private int index;

    /** @param tokens the tokens of one statement, without its semicolon; must not be empty */
    TokenCursor(List<Token> tokens) {
        this.tokens = tokens;
        SourceSpan last = tokens.get(tokens.size() - 1).span();
        int endColumn = last.column() + last.length();
        this.end =
                new Token(
                        TokenType.EOF,
                        "",
                        "",
                        new SourceSpan(last.endOffset(), last.endOffset(), last.line(), endColumn));
    }

    Token peek() {
        return peek(0);
    }

    Token peek(int ahead) {
        int i = index + ahead;
        return i < tokens.size() ? tokens.get(i) : end;
    }

    Token next() {
        Token token = peek();
        if (index < tokens.size()) {
            index++;
        }
        return token;
    }

    boolean atEnd() {
        return index >= tokens.size();
    }

    int position() {
        return index;
    }

    List<Token> slice(int from, int to) {
        return tokens.subList(from, to);
    }

    boolean is(TokenType type) {
        return peek().type() == type;
    }

    boolean isWord(String keyword) {
        return peek().isWord(keyword);
    }

    boolean isWordAt(int ahead, String keyword) {
        return peek(ahead).isWord(keyword);
    }

    boolean accept(TokenType type) {
        if (is(type)) {
            index++;
            return true;
        }
        return false;
    }

    boolean acceptWord(String keyword) {
        if (isWord(keyword)) {
            index++;
            return true;
        }
        return false;
    }

    /** Consumes the given keywords only if all of them come next, in order. */
    boolean acceptWords(String... keywords) {
        for (int i = 0; i < keywords.length; i++) {
            if (!isWordAt(i, keywords[i])) {
                return false;
            }
        }
        index += keywords.length;
        return true;
    }

    void expectWord(String keyword) {
        if (!acceptWord(keyword)) {
            throw expected(keyword.toUpperCase(java.util.Locale.ROOT));
        }
    }

    Token expect(TokenType type, String description) {
        if (!is(type)) {
            throw expected(description);
        }
        return next();
    }

    /** Consumes an identifier, quoted or not, and returns its name as PostgreSQL would store it. */
    String identifier(String description) {
        Token token = peek();
        if (token.type() != TokenType.WORD && token.type() != TokenType.QUOTED_IDENTIFIER) {
            throw expected(description);
        }
        index++;
        return token.value();
    }

    /** Consumes {@code ( ... )} and returns the tokens between the matching parentheses. */
    List<Token> balancedParentheses() {
        Token open = expect(TokenType.LPAREN, "'('");
        List<Token> inner = new ArrayList<>();
        int depth = 1;
        while (!atEnd()) {
            Token token = next();
            if (token.type() == TokenType.LPAREN) {
                depth++;
            } else if (token.type() == TokenType.RPAREN && --depth == 0) {
                return inner;
            }
            inner.add(token);
        }
        throw ParseError.syntax("Unbalanced parentheses: this '(' is never closed", open);
    }

    ParseError expected(String description) {
        return ParseError.syntax(
                "Expected " + description + " but found " + describe(peek()), peek());
    }

    static String describe(Token token) {
        return token.type() == TokenType.EOF ? "end of statement" : "'" + token.text() + "'";
    }
}
