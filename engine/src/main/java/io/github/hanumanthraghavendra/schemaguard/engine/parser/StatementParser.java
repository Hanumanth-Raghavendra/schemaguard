package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic;
import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Severity;
import io.github.hanumanthraghavendra.schemaguard.engine.lexer.Token;
import io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.EnumType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ObjectKind;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Persistence;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Table;
import io.github.hanumanthraghavendra.schemaguard.engine.source.SourceSpan;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Parses the tokens of a single statement: works out which kind it is, then parses it or hands
 * it to the parser for that kind. One instance is used once.
 */
final class StatementParser extends ParserBase {

    /** Statements that do not change schema structure and are skipped without a warning. */
    private static final Set<String> IGNORED_LEADING_KEYWORDS =
            Set.of(
                    "set", "reset", "show", "select", "comment", "grant", "revoke", "begin",
                    "start", "commit", "end", "rollback", "savepoint", "release", "analyze",
                    "vacuum", "copy", "insert");

    /** Words that may sit between the verb and the object type, as in CREATE UNIQUE INDEX. */
    private static final Set<String> OBJECT_MODIFIERS =
            Set.of(
                    "or", "replace", "unique", "materialized", "temp", "temporary", "unlogged",
                    "global", "local", "foreign", "event", "recursive", "default");

    private static final Set<String> OBJECT_VERBS = Set.of("create", "alter", "drop");

    private static final Set<String> PERSISTENCE_KEYWORDS =
            Set.of("global", "local", "temporary", "temp", "unlogged");

    private final List<Token> tokens;
    private final List<Diagnostic> diagnostics;
    private final TableElementParser elements;

    StatementParser(List<Token> tokens, ParserOptions options, List<Diagnostic> diagnostics) {
        super(new TokenCursor(tokens), options, spanOf(tokens));
        this.tokens = tokens;
        this.diagnostics = diagnostics;
        this.elements = new TableElementParser(this);
    }

    private static SourceSpan spanOf(List<Token> tokens) {
        SourceSpan first = tokens.get(0).span();
        SourceSpan last = tokens.get(tokens.size() - 1).span();
        return new SourceSpan(first.startOffset(), last.endOffset(), first.line(), first.column());
    }

    Statement parse() {
        try {
            return dispatch();
        } catch (ParseError error) {
            diagnostics.add(error.toDiagnostic());
            return error.isUnsupported()
                    ? new Statement.Unsupported(kind(), span)
                    : new Statement.Invalid(span);
        }
    }

    // ---------------------------------------------------------------- dispatch

    private Statement dispatch() {
        Token first = cursor.peek();
        if (first.type() != TokenType.WORD) {
            throw ParseError.syntax(
                    "Expected a statement but found " + TokenCursor.describe(first), first);
        }
        Statement statement =
                switch (first.value()) {
                    case "create" -> dispatchCreate();
                    case "alter" -> dispatchAlter();
                    case "drop" -> dispatchDrop();
                    default -> IGNORED_LEADING_KEYWORDS.contains(first.value()) ? ignored() : null;
                };
        if (statement != null) {
            return statement;
        }
        diagnostics.add(
                new Diagnostic(
                        Severity.WARNING,
                        ParseErrors.UNSUPPORTED_STATEMENT,
                        "Unsupported statement: " + kind() + ". It was not analysed.",
                        span));
        return new Statement.Unsupported(kind(), span);
    }

    /** Returns null for a CREATE statement that is not handled. */
    private Statement dispatchCreate() {
        if (isCreate("table")) {
            return parseCreateTable();
        }
        if (isCreate("sequence")) {
            return new SequenceParser(this).parseCreate();
        }
        boolean uniqueIndex = cursor.isWordAt(1, "unique") && cursor.isWordAt(2, "index");
        if (uniqueIndex || cursor.isWordAt(1, "index")) {
            return new IndexParser(this).parse();
        }
        if (cursor.isWordAt(1, "type")) {
            return parseCreateType();
        }
        if (cursor.isWordAt(1, "schema") || cursor.isWordAt(1, "extension")) {
            return ignored();
        }
        return null;
    }

    /** Returns null for an ALTER statement that is not handled. */
    private Statement dispatchAlter() {
        boolean defaultPrivileges = cursor.isWordAt(1, "default") && cursor.isWordAt(2, "privileges");
        if (defaultPrivileges || isOwnerChange()) {
            return ignored();
        }
        if (cursor.isWordAt(1, "table")) {
            return new AlterTableParser(this, elements).parse();
        }
        if (cursor.isWordAt(1, "sequence")) {
            return new SequenceParser(this).parseAlter();
        }
        if (cursor.isWordAt(1, "type")) {
            return parseAlterType();
        }
        return null;
    }

    /** Returns null for a DROP of an object kind that is not modelled. */
    private Statement dispatchDrop() {
        for (ObjectKind kind : ObjectKind.values()) {
            if (cursor.isWordAt(1, kind.keyword())) {
                return parseDrop(kind);
            }
        }
        return null;
    }

    private Statement ignored() {
        return new Statement.Ignored(kind(), span);
    }

    /** True for {@code CREATE [GLOBAL | LOCAL] [TEMP | TEMPORARY | UNLOGGED] <object>}. */
    private boolean isCreate(String object) {
        int i = 1;
        while (cursor.peek(i).type() == TokenType.WORD
                && PERSISTENCE_KEYWORDS.contains(cursor.peek(i).value())) {
            i++;
        }
        return cursor.isWordAt(i, object);
    }

    /** True for {@code ALTER <anything> ... OWNER TO role}, which pg_dump emits for every object. */
    private boolean isOwnerChange() {
        int n = tokens.size();
        return n >= 5 && tokens.get(n - 3).isWord("owner") && tokens.get(n - 2).isWord("to");
    }

    /**
     * The statement's leading keywords in upper case: the verb alone for most statements, and
     * the verb plus object type for CREATE, ALTER and DROP, for example {@code CREATE UNIQUE INDEX}.
     */
    private String kind() {
        String verb = tokens.get(0).value();
        StringBuilder kind = new StringBuilder(verb.toUpperCase(Locale.ROOT));
        if (!OBJECT_VERBS.contains(verb)) {
            return kind.toString();
        }
        for (int i = 1; i < tokens.size() && tokens.get(i).type() == TokenType.WORD; i++) {
            Token token = tokens.get(i);
            if (!token.isWord("or") && !token.isWord("replace")) {
                kind.append(' ').append(token.value().toUpperCase(Locale.ROOT));
            }
            if (!OBJECT_MODIFIERS.contains(token.value())) {
                break;
            }
        }
        return kind.toString();
    }

    // ---------------------------------------------------------------- CREATE TABLE

    private Statement parseCreateTable() {
        cursor.expectWord("create");
        Persistence persistence = Persistence.PERMANENT;
        if (!cursor.acceptWord("global")) {
            cursor.acceptWord("local");
        }
        if (cursor.acceptWord("temporary") || cursor.acceptWord("temp")) {
            persistence = Persistence.TEMPORARY;
        } else if (cursor.acceptWord("unlogged")) {
            persistence = Persistence.UNLOGGED;
        }
        cursor.expectWord("table");
        boolean ifNotExists = ifNotExists();
        QualifiedName name = qualifiedName("a table name");

        if (cursor.isWord("partition") && cursor.isWordAt(1, "of")) {
            throw ParseError.unsupported("PARTITION OF", cursor.peek());
        }
        if (cursor.isWord("of")) {
            throw ParseError.unsupported("typed tables (OF type)", cursor.peek());
        }
        if (cursor.isWord("as")) {
            throw ParseError.unsupported("CREATE TABLE AS", cursor.peek());
        }

        TableElementParser.TableBuilder builder = elements.newTable();
        cursor.expect(TokenType.LPAREN, "'('");
        if (!cursor.accept(TokenType.RPAREN)) {
            do {
                elements.parseTableElement(builder);
            } while (cursor.accept(TokenType.COMMA));
            cursor.expect(TokenType.RPAREN, "',' or ')'");
        }

        List<QualifiedName> inherits = new ArrayList<>();
        Optional<String> partitionBy = Optional.empty();
        while (!cursor.atEnd()) {
            if (cursor.acceptWord("inherits")) {
                cursor.expect(TokenType.LPAREN, "'('");
                do {
                    inherits.add(qualifiedName("a parent table name"));
                } while (cursor.accept(TokenType.COMMA));
                cursor.expect(TokenType.RPAREN, "')'");
            } else if (cursor.acceptWords("partition", "by")) {
                String method = cursor.identifier("a partitioning method");
                String key = ExpressionRenderer.render(cursor.balancedParentheses());
                partitionBy = Optional.of(method + " (" + key + ")");
            } else if (cursor.acceptWord("using")) {
                cursor.identifier("an access method");
            } else if (cursor.acceptWord("with")) {
                cursor.balancedParentheses();
            } else if (cursor.acceptWords("without", "oids")) {
                // Historical no-op.
            } else if (cursor.acceptWords("on", "commit")) {
                boolean valid =
                        cursor.acceptWords("preserve", "rows")
                                || cursor.acceptWords("delete", "rows")
                                || cursor.acceptWord("drop");
                if (!valid) {
                    throw cursor.expected("PRESERVE ROWS, DELETE ROWS or DROP");
                }
            } else if (cursor.acceptWord("tablespace")) {
                cursor.identifier("a tablespace name");
            } else if (cursor.isWord("as")) {
                throw ParseError.unsupported("CREATE TABLE AS", cursor.peek());
            } else {
                throw ParseError.syntax(
                        "Unexpected " + TokenCursor.describe(cursor.peek()) + " after the table definition",
                        cursor.peek());
            }
        }

        Table table = builder.build(name, persistence, inherits, partitionBy);
        return new Statement.CreateTable(table, ifNotExists, span);
    }

    // ---------------------------------------------------------------- enumerated types

    private Statement parseCreateType() {
        cursor.expectWord("create");
        cursor.expectWord("type");
        QualifiedName name = qualifiedName("a type name");
        if (!cursor.acceptWords("as", "enum")) {
            throw ParseError.unsupported("CREATE TYPE forms other than ENUM", cursor.peek());
        }
        cursor.expect(TokenType.LPAREN, "'('");
        List<String> labels = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (!cursor.is(TokenType.RPAREN)) {
            do {
                Token at = cursor.peek();
                String label = stringLiteral("an enum label");
                if (!seen.add(label)) {
                    throw ParseError.syntax("Enum label '" + label + "' is specified more than once", at);
                }
                labels.add(label);
            } while (cursor.accept(TokenType.COMMA));
        }
        cursor.expect(TokenType.RPAREN, "',' or ')'");
        expectEndOfStatement();
        return new Statement.CreateEnum(new EnumType(name, labels), span);
    }

    private Statement parseAlterType() {
        cursor.expectWord("alter");
        cursor.expectWord("type");
        QualifiedName name = qualifiedName("a type name");
        if (cursor.acceptWords("add", "value")) {
            boolean ifNotExists = ifNotExists();
            String value = stringLiteral("an enum label");
            Optional<String> before = Optional.empty();
            Optional<String> after = Optional.empty();
            if (cursor.acceptWord("before")) {
                before = Optional.of(stringLiteral("an enum label"));
            } else if (cursor.acceptWord("after")) {
                after = Optional.of(stringLiteral("an enum label"));
            }
            expectEndOfStatement();
            return new Statement.AddEnumValue(name, value, ifNotExists, before, after, span);
        }
        if (cursor.acceptWords("rename", "value")) {
            String from = stringLiteral("an enum label");
            cursor.expectWord("to");
            String to = stringLiteral("an enum label");
            expectEndOfStatement();
            return new Statement.RenameEnumValue(name, from, to, span);
        }
        throw ParseError.unsupported(
                "ALTER TYPE forms other than ADD VALUE and RENAME VALUE", cursor.peek());
    }

    // ---------------------------------------------------------------- DROP

    private Statement parseDrop(ObjectKind kind) {
        cursor.expectWord("drop");
        cursor.expectWord(kind.keyword());
        boolean concurrently = kind == ObjectKind.INDEX && cursor.acceptWord("concurrently");
        boolean ifExists = ifExists();
        List<QualifiedName> names = new ArrayList<>();
        do {
            names.add(qualifiedName("an object name"));
        } while (cursor.accept(TokenType.COMMA));
        boolean cascade = cascade();
        expectEndOfStatement();
        return new Statement.Drop(kind, names, ifExists, cascade, concurrently, span);
    }
}
