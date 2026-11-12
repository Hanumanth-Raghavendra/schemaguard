package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import io.github.hanumanthraghavendra.schemaguard.engine.lexer.Token;
import io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType;
import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a token stream into statements at semicolons.
 *
 * <p>Two constructs legitimately contain semicolons and are kept whole: the body of an
 * SQL-standard routine ({@code BEGIN ATOMIC ... END}) and the action list of
 * {@code CREATE RULE ... DO ( ...; ... )}. Dollar-quoted bodies need no care here because the
 * lexer already returns each as a single token.
 */
final class StatementSplitter {

    private StatementSplitter() {}

    static List<List<Token>> split(List<Token> tokens) {
        List<List<Token>> statements = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int parenDepth = 0;
        int blockDepth = 0;

        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            TokenType type = token.type();
            if (type == TokenType.META_COMMAND || type == TokenType.COPY_DATA || type == TokenType.EOF) {
                continue;
            }
            if (type == TokenType.SEMICOLON) {
                boolean insideRuleActions = parenDepth > 0 && isCreateRule(current);
                if (blockDepth == 0 && !insideRuleActions) {
                    if (!current.isEmpty()) {
                        statements.add(List.copyOf(current));
                        current.clear();
                    }
                    parenDepth = 0;
                    continue;
                }
            } else if (type == TokenType.LPAREN) {
                parenDepth++;
            } else if (type == TokenType.RPAREN) {
                parenDepth = Math.max(0, parenDepth - 1);
            } else if (blockDepth > 0) {
                // Inside BEGIN ATOMIC, a CASE expression has its own END.
                if (token.isWord("case")) {
                    blockDepth++;
                } else if (token.isWord("end")) {
                    blockDepth--;
                }
            } else if (token.isWord("begin")
                    && i + 1 < tokens.size()
                    && tokens.get(i + 1).isWord("atomic")) {
                blockDepth = 1;
            }
            current.add(token);
        }
        if (!current.isEmpty()) {
            statements.add(List.copyOf(current));
        }
        return statements;
    }

    private static boolean isCreateRule(List<Token> statement) {
        if (statement.size() < 2 || !statement.get(0).isWord("create")) {
            return false;
        }
        if (statement.get(1).isWord("rule")) {
            return true;
        }
        return statement.size() >= 4
                && statement.get(1).isWord("or")
                && statement.get(2).isWord("replace")
                && statement.get(3).isWord("rule");
    }
}
