package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import io.github.hanumanthraghavendra.schemaguard.engine.lexer.Token;
import io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.DataType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.TypeNames;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses a type name into canonical form.
 *
 * <p>SQL type names are irregular: some span several words ({@code double precision},
 * {@code timestamp with time zone}), some put their precision in the middle
 * ({@code timestamp(3) with time zone}) and many have aliases ({@code int4}, {@code varchar}).
 * All of that is resolved here so the rest of the engine compares one spelling per type.
 */
final class TypeParser {

    private static final Set<String> INTERVAL_FIELDS =
            Set.of("year", "month", "day", "hour", "minute", "second", "to");

    private final TokenCursor cursor;

    private TypeParser(TokenCursor cursor) {
        this.cursor = cursor;
    }

    static DataType parse(TokenCursor cursor) {
        return new TypeParser(cursor).parseType();
    }

    private DataType parseType() {
        Token first = cursor.peek();
        String schema = null;
        String name;
        List<String> modifiers = new ArrayList<>();
        String zoneSuffix = "";

        String keyword = first.type() == TokenType.WORD ? first.value() : "";
        switch (keyword) {
            case "national" -> {
                cursor.next();
                if (!cursor.acceptWord("character") && !cursor.acceptWord("char")) {
                    throw cursor.expected("CHARACTER");
                }
                name = cursor.acceptWord("varying") ? "character varying" : "character";
            }
            case "character", "char", "nchar" -> {
                cursor.next();
                name = cursor.acceptWord("varying") ? "character varying" : "character";
            }
            case "bit" -> {
                cursor.next();
                name = cursor.acceptWord("varying") ? "bit varying" : "bit";
            }
            case "double" -> {
                cursor.next();
                cursor.expectWord("precision");
                name = "double precision";
            }
            case "timestamp", "time", "timestamptz", "timetz" -> {
                cursor.next();
                name = keyword.startsWith("timestamp") ? "timestamp" : "time";
                boolean withZone = keyword.endsWith("tz");
                if (cursor.is(TokenType.LPAREN)) {
                    modifiers.addAll(modifierList());
                }
                if (cursor.acceptWords("with", "time", "zone")) {
                    withZone = true;
                } else {
                    cursor.acceptWords("without", "time", "zone");
                }
                zoneSuffix = withZone ? " with time zone" : " without time zone";
            }
            case "interval" -> {
                cursor.next();
                StringBuilder fields = new StringBuilder("interval");
                while (cursor.peek().type() == TokenType.WORD
                        && INTERVAL_FIELDS.contains(cursor.peek().value())) {
                    fields.append(' ').append(cursor.next().value());
                }
                name = fields.toString();
            }
            default -> {
                List<Token> parts = new ArrayList<>();
                parts.add(identifierToken());
                while (cursor.accept(TokenType.DOT)) {
                    parts.add(identifierToken());
                }
                Token last = parts.get(parts.size() - 1);
                boolean unquoted = last.type() == TokenType.WORD;
                if (parts.size() == 1) {
                    name = unquoted ? TypeNames.canonical(last.value()) : last.value();
                } else {
                    Token qualifier = parts.get(parts.size() - 2);
                    if (qualifier.isWord("pg_catalog") && unquoted) {
                        name = TypeNames.canonical(last.value());
                    } else {
                        schema = qualifier.value();
                        name = last.value();
                    }
                }
            }
        }

        if (cursor.is(TokenType.LPAREN)) {
            modifiers.addAll(modifierList());
        }
        if (schema == null) {
            name = applyBuiltInDefaults(name, modifiers);
        }
        return new DataType(schema, name + zoneSuffix, modifiers, arrayDimensions());
    }

    private Token identifierToken() {
        Token token = cursor.peek();
        cursor.identifier("a type name");
        return token;
    }

    /** Parses {@code (a, b)} into the text of each comma-separated modifier. */
    private List<String> modifierList() {
        Token open = cursor.peek();
        List<Token> inner = cursor.balancedParentheses();
        List<String> modifiers = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (Token token : inner) {
            if (token.type() == TokenType.COMMA && depth == 0) {
                modifiers.add(current.toString());
                current.setLength(0);
                continue;
            }
            if (token.type() == TokenType.LPAREN) {
                depth++;
            } else if (token.type() == TokenType.RPAREN) {
                depth--;
            }
            current.append(token.text());
        }
        modifiers.add(current.toString());
        if (modifiers.stream().anyMatch(String::isEmpty)) {
            throw ParseError.syntax("Empty type modifier", open);
        }
        return modifiers;
    }

    /**
     * Fills in what PostgreSQL assumes when a built-in type is written in short form, so the
     * short and long spellings compare equal. May rewrite {@code modifiers}.
     */
    private static String applyBuiltInDefaults(String name, List<String> modifiers) {
        switch (name) {
            case "character", "bit" -> {
                if (modifiers.isEmpty()) {
                    modifiers.add("1");
                }
            }
            case "numeric" -> {
                if (modifiers.size() == 1) {
                    modifiers.add("0");
                }
            }
            case "float" -> {
                // float(1..24) is real; float(25..53) and plain float are double precision.
                boolean single = modifiers.size() == 1 && isAtMost(modifiers.get(0), 24);
                modifiers.clear();
                return single ? "real" : "double precision";
            }
            default -> {
                // No defaults to apply.
            }
        }
        return name;
    }

    private static boolean isAtMost(String digits, int limit) {
        try {
            return Integer.parseInt(digits) <= limit;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private int arrayDimensions() {
        if (cursor.acceptWord("array")) {
            if (cursor.accept(TokenType.LBRACKET)) {
                cursor.accept(TokenType.NUMBER);
                cursor.expect(TokenType.RBRACKET, "']'");
            }
            return 1;
        }
        int dimensions = 0;
        while (cursor.accept(TokenType.LBRACKET)) {
            cursor.accept(TokenType.NUMBER);
            cursor.expect(TokenType.RBRACKET, "']'");
            dimensions++;
        }
        return dimensions;
    }
}
