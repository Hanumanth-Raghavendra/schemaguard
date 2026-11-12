package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic;
import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Severity;
import io.github.hanumanthraghavendra.schemaguard.engine.lexer.Token;
import io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Check;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Column;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Constraint;
import io.github.hanumanthraghavendra.schemaguard.engine.model.DataType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Deferrability;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Expression;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ForeignKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.GeneratedColumn;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Identifiers;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Identity;
import io.github.hanumanthraghavendra.schemaguard.engine.model.MatchType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Persistence;
import io.github.hanumanthraghavendra.schemaguard.engine.model.PrimaryKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ReferentialAction;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Table;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Unique;
import io.github.hanumanthraghavendra.schemaguard.engine.source.SourceSpan;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Parses the tokens of a single statement. One instance is used once. */
final class StatementParser {

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

    /** Words that end a column DEFAULT expression when met outside parentheses. */
    private static final Set<String> COLUMN_CONSTRAINT_KEYWORDS =
            Set.of(
                    "constraint", "not", "null", "check", "default", "generated", "unique",
                    "primary", "references", "deferrable", "initially", "collate");

    private final List<Token> tokens;
    private final TokenCursor cursor;
    private final ParserOptions options;
    private final List<Diagnostic> diagnostics;
    private final SourceSpan span;

    StatementParser(List<Token> tokens, ParserOptions options, List<Diagnostic> diagnostics) {
        this.tokens = tokens;
        this.cursor = new TokenCursor(tokens);
        this.options = options;
        this.diagnostics = diagnostics;
        SourceSpan first = tokens.get(0).span();
        SourceSpan last = tokens.get(tokens.size() - 1).span();
        this.span = new SourceSpan(first.startOffset(), last.endOffset(), first.line(), first.column());
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
        String verb = first.value();
        if (verb.equals("create")) {
            if (isCreateTable()) {
                return parseCreateTable();
            }
            if (cursor.isWordAt(1, "schema") || cursor.isWordAt(1, "extension")) {
                return new Statement.Ignored(kind(), span);
            }
        } else if (verb.equals("alter")) {
            boolean defaultPrivileges =
                    cursor.isWordAt(1, "default") && cursor.isWordAt(2, "privileges");
            if (defaultPrivileges || isOwnerChange()) {
                return new Statement.Ignored(kind(), span);
            }
        } else if (IGNORED_LEADING_KEYWORDS.contains(verb)) {
            return new Statement.Ignored(kind(), span);
        }

        diagnostics.add(
                new Diagnostic(
                        Severity.WARNING,
                        ParseErrors.UNSUPPORTED_STATEMENT,
                        "Unsupported statement: " + kind() + ". It was not analysed.",
                        span));
        return new Statement.Unsupported(kind(), span);
    }

    private boolean isCreateTable() {
        int i = 1;
        while (cursor.isWordAt(i, "global")
                || cursor.isWordAt(i, "local")
                || cursor.isWordAt(i, "temporary")
                || cursor.isWordAt(i, "temp")
                || cursor.isWordAt(i, "unlogged")) {
            i++;
        }
        return cursor.isWordAt(i, "table");
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
        boolean ifNotExists = cursor.acceptWords("if", "not", "exists");
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

        TableBuilder builder = new TableBuilder();
        cursor.expect(TokenType.LPAREN, "'('");
        if (!cursor.accept(TokenType.RPAREN)) {
            do {
                parseTableElement(builder);
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

    private void parseTableElement(TableBuilder builder) {
        if (cursor.isWord("like")) {
            throw ParseError.unsupported("LIKE in CREATE TABLE", cursor.peek());
        }
        if (startsTableConstraint()) {
            parseTableConstraint(builder);
        } else {
            parseColumn(builder);
        }
    }

    private boolean startsTableConstraint() {
        Token token = cursor.peek();
        if (token.type() != TokenType.WORD) {
            return false;
        }
        return switch (token.value()) {
            case "constraint", "check", "unique", "primary", "foreign" -> true;
            // EXCLUDE is not reserved, so a column may be called "exclude".
            case "exclude" -> cursor.peek(1).type() == TokenType.LPAREN || cursor.isWordAt(1, "using");
            // PostgreSQL 18 allows NOT NULL as a table constraint: NOT NULL column_name.
            case "not" -> cursor.isWordAt(1, "null");
            default -> false;
        };
    }

    // ---------------------------------------------------------------- columns

    private void parseColumn(TableBuilder builder) {
        Token nameToken = cursor.peek();
        String name = cursor.identifier("a column name");
        DataType type = TypeParser.parse(cursor);

        boolean notNull = false;
        Optional<Expression> defaultValue = Optional.empty();
        Identity identity = Identity.NONE;
        Optional<GeneratedColumn> generated = Optional.empty();
        Optional<String> collation = Optional.empty();

        while (true) {
            if (cursor.acceptWord("collate")) {
                collation = Optional.of(dottedName("a collation name"));
                continue;
            }
            if (cursor.acceptWord("storage") || cursor.acceptWord("compression")) {
                cursor.identifier("a setting");
                continue;
            }

            Optional<String> constraintName = optionalConstraintName();
            if (cursor.acceptWords("not", "null")) {
                notNull = true;
                cursor.acceptWords("no", "inherit");
            } else if (cursor.acceptWord("null")) {
                // Explicitly nullable, which is the default.
            } else if (cursor.acceptWord("default")) {
                defaultValue = Optional.of(defaultExpression());
            } else if (cursor.acceptWord("check")) {
                Expression condition = parenthesizedExpression();
                boolean noInherit = cursor.acceptWords("no", "inherit");
                builder.constraints.add(
                        constraintAttributes(new Check(constraintName, condition, noInherit, false)));
            } else if (cursor.acceptWord("unique")) {
                boolean nullsNotDistinct = nullsNotDistinct();
                List<String> include = indexParameters();
                builder.constraints.add(
                        constraintAttributes(
                                new Unique(
                                        constraintName,
                                        List.of(name),
                                        include,
                                        nullsNotDistinct,
                                        Deferrability.NOT_DEFERRABLE)));
            } else if (cursor.acceptWords("primary", "key")) {
                List<String> include = indexParameters();
                builder.constraints.add(
                        constraintAttributes(
                                new PrimaryKey(
                                        constraintName,
                                        List.of(name),
                                        include,
                                        Deferrability.NOT_DEFERRABLE)));
            } else if (cursor.acceptWord("references")) {
                builder.constraints.add(
                        constraintAttributes(referencesClause(constraintName, List.of(name))));
            } else if (cursor.acceptWord("generated")) {
                boolean always = cursor.acceptWord("always");
                if (!always && !cursor.acceptWords("by", "default")) {
                    throw cursor.expected("ALWAYS or BY DEFAULT");
                }
                cursor.expectWord("as");
                if (cursor.acceptWord("identity")) {
                    identity = always ? Identity.ALWAYS : Identity.BY_DEFAULT;
                    if (cursor.is(TokenType.LPAREN)) {
                        cursor.balancedParentheses();
                    }
                } else {
                    if (!always) {
                        throw ParseError.syntax(
                                "A generated column must be GENERATED ALWAYS", cursor.peek());
                    }
                    Expression expression = parenthesizedExpression();
                    boolean stored = cursor.acceptWord("stored");
                    if (!stored) {
                        cursor.acceptWord("virtual");
                    }
                    generated = Optional.of(new GeneratedColumn(expression, stored));
                }
            } else if (constraintName.isPresent()) {
                throw cursor.expected("a constraint definition");
            } else {
                break;
            }
        }

        // An identity column is implicitly NOT NULL.
        boolean effectiveNotNull = notNull || identity != Identity.NONE;
        builder.addColumn(
                new Column(name, type, effectiveNotNull, defaultValue, identity, generated, collation),
                nameToken);
    }

    /**
     * Reads a DEFAULT expression. Its grammar forbids bare AND, OR, NOT and IS, so the
     * expression ends at the first top-level comma, closing parenthesis or keyword that starts
     * another column constraint.
     */
    private Expression defaultExpression() {
        int from = cursor.position();
        int depth = 0;
        while (!cursor.atEnd()) {
            Token token = cursor.peek();
            TokenType type = token.type();
            boolean started = cursor.position() > from;
            if (depth == 0) {
                if (type == TokenType.COMMA || type == TokenType.RPAREN) {
                    break;
                }
                if (started
                        && type == TokenType.WORD
                        && COLUMN_CONSTRAINT_KEYWORDS.contains(token.value())) {
                    break;
                }
            }
            if (type == TokenType.LPAREN || type == TokenType.LBRACKET) {
                depth++;
            } else if (type == TokenType.RPAREN || type == TokenType.RBRACKET) {
                depth--;
            }
            cursor.next();
        }
        if (cursor.position() == from) {
            throw cursor.expected("an expression after DEFAULT");
        }
        return ExpressionRenderer.toExpression(cursor.slice(from, cursor.position()));
    }

    // ---------------------------------------------------------------- constraints

    private void parseTableConstraint(TableBuilder builder) {
        Optional<String> name = optionalConstraintName();
        Token start = cursor.peek();

        if (cursor.acceptWords("not", "null")) {
            builder.notNullColumns.add(cursor.identifier("a column name"));
            cursor.acceptWords("no", "inherit");
            return;
        }

        Constraint constraint;
        if (cursor.acceptWord("check")) {
            Expression condition = parenthesizedExpression();
            constraint = new Check(name, condition, cursor.acceptWords("no", "inherit"), false);
        } else if (cursor.acceptWord("unique")) {
            boolean nullsNotDistinct = nullsNotDistinct();
            List<String> columns = columnList();
            constraint =
                    new Unique(
                            name,
                            columns,
                            indexParameters(),
                            nullsNotDistinct,
                            Deferrability.NOT_DEFERRABLE);
        } else if (cursor.acceptWords("primary", "key")) {
            List<String> columns = columnList();
            constraint = new PrimaryKey(name, columns, indexParameters(), Deferrability.NOT_DEFERRABLE);
        } else if (cursor.acceptWords("foreign", "key")) {
            List<String> columns = columnList();
            cursor.expectWord("references");
            constraint = referencesClause(name, columns);
        } else if (cursor.isWord("exclude")) {
            throw ParseError.unsupported("EXCLUDE constraints", start);
        } else {
            throw cursor.expected("a constraint definition");
        }
        builder.constraints.add(constraintAttributes(constraint));
    }

    private Optional<String> optionalConstraintName() {
        return cursor.acceptWord("constraint")
                ? Optional.of(cursor.identifier("a constraint name"))
                : Optional.empty();
    }

    private boolean nullsNotDistinct() {
        if (!cursor.acceptWord("nulls")) {
            return false;
        }
        boolean not = cursor.acceptWord("not");
        cursor.expectWord("distinct");
        return not;
    }

    /** Reads the optional INCLUDE, WITH and USING INDEX TABLESPACE clauses; returns the INCLUDE columns. */
    private List<String> indexParameters() {
        List<String> include = List.of();
        while (true) {
            if (cursor.acceptWord("include")) {
                include = columnList();
            } else if (cursor.acceptWord("with")) {
                cursor.balancedParentheses();
            } else if (cursor.acceptWords("using", "index", "tablespace")) {
                cursor.identifier("a tablespace name");
            } else {
                return include;
            }
        }
    }

    /** Parses what follows the REFERENCES keyword. */
    private ForeignKey referencesClause(Optional<String> name, List<String> columns) {
        QualifiedName referencedTable = qualifiedName("a referenced table name");
        List<String> referencedColumns = cursor.is(TokenType.LPAREN) ? columnList() : List.of();
        MatchType match = MatchType.SIMPLE;
        ReferentialAction onDelete = ReferentialAction.NO_ACTION;
        ReferentialAction onUpdate = ReferentialAction.NO_ACTION;
        List<String> onDeleteColumns = List.of();

        while (true) {
            if (cursor.acceptWord("match")) {
                if (cursor.acceptWord("full")) {
                    match = MatchType.FULL;
                } else if (cursor.acceptWord("partial")) {
                    match = MatchType.PARTIAL;
                } else if (cursor.acceptWord("simple")) {
                    match = MatchType.SIMPLE;
                } else {
                    throw cursor.expected("FULL, PARTIAL or SIMPLE");
                }
            } else if (cursor.acceptWords("on", "delete")) {
                onDelete = referentialAction();
                boolean setAction =
                        onDelete == ReferentialAction.SET_NULL
                                || onDelete == ReferentialAction.SET_DEFAULT;
                if (setAction && cursor.is(TokenType.LPAREN)) {
                    onDeleteColumns = columnList();
                }
            } else if (cursor.acceptWords("on", "update")) {
                onUpdate = referentialAction();
            } else {
                break;
            }
        }
        return new ForeignKey(
                name, columns, referencedTable, referencedColumns, onDelete, onUpdate,
                onDeleteColumns, match, Deferrability.NOT_DEFERRABLE, false);
    }

    private ReferentialAction referentialAction() {
        if (cursor.acceptWords("no", "action")) {
            return ReferentialAction.NO_ACTION;
        }
        if (cursor.acceptWord("restrict")) {
            return ReferentialAction.RESTRICT;
        }
        if (cursor.acceptWord("cascade")) {
            return ReferentialAction.CASCADE;
        }
        if (cursor.acceptWords("set", "null")) {
            return ReferentialAction.SET_NULL;
        }
        if (cursor.acceptWords("set", "default")) {
            return ReferentialAction.SET_DEFAULT;
        }
        throw cursor.expected("NO ACTION, RESTRICT, CASCADE, SET NULL or SET DEFAULT");
    }

    /** Reads the DEFERRABLE, INITIALLY and NOT VALID clauses that may follow a constraint. */
    private Constraint constraintAttributes(Constraint constraint) {
        Token start = cursor.peek();
        Boolean deferrable = null;
        Boolean initiallyDeferred = null;
        boolean notValid = false;
        while (true) {
            if (cursor.acceptWord("deferrable")) {
                deferrable = true;
            } else if (cursor.acceptWords("not", "deferrable")) {
                deferrable = false;
            } else if (cursor.acceptWords("initially", "deferred")) {
                initiallyDeferred = true;
            } else if (cursor.acceptWords("initially", "immediate")) {
                initiallyDeferred = false;
            } else if (cursor.acceptWords("not", "valid")) {
                notValid = true;
            } else if (cursor.isWord("not") && cursor.isWordAt(1, "enforced")) {
                throw ParseError.unsupported("NOT ENFORCED constraints", cursor.peek());
            } else if (!cursor.acceptWord("enforced")) {
                break;
            }
        }

        if (Boolean.FALSE.equals(deferrable) && Boolean.TRUE.equals(initiallyDeferred)) {
            throw ParseError.syntax(
                    "A constraint declared INITIALLY DEFERRED must be DEFERRABLE", start);
        }
        Deferrability deferrability =
                Boolean.TRUE.equals(initiallyDeferred)
                        ? Deferrability.DEFERRABLE_INITIALLY_DEFERRED
                        : Boolean.TRUE.equals(deferrable)
                                ? Deferrability.DEFERRABLE_INITIALLY_IMMEDIATE
                                : Deferrability.NOT_DEFERRABLE;
        boolean deferred = deferrability != Deferrability.NOT_DEFERRABLE;

        return switch (constraint) {
            case PrimaryKey primaryKey -> {
                rejectNotValid(notValid, "PRIMARY KEY", start);
                yield primaryKey.withDeferrability(deferrability);
            }
            case Unique unique -> {
                rejectNotValid(notValid, "UNIQUE", start);
                yield unique.withDeferrability(deferrability);
            }
            case ForeignKey foreignKey ->
                    foreignKey.withDeferrability(deferrability).withNotValid(notValid);
            case Check check -> {
                if (deferred) {
                    throw ParseError.syntax("CHECK constraints cannot be marked DEFERRABLE", start);
                }
                yield check.withNotValid(notValid);
            }
        };
    }

    private static void rejectNotValid(boolean notValid, String constraintKind, Token at) {
        if (notValid) {
            throw ParseError.syntax(constraintKind + " constraints cannot be marked NOT VALID", at);
        }
    }

    // ---------------------------------------------------------------- shared pieces

    private Expression parenthesizedExpression() {
        Token open = cursor.peek();
        List<Token> inner = cursor.balancedParentheses();
        if (inner.isEmpty()) {
            throw ParseError.syntax("Expected an expression inside the parentheses", open);
        }
        return ExpressionRenderer.toExpression(inner);
    }

    private List<String> columnList() {
        cursor.expect(TokenType.LPAREN, "'('");
        List<String> columns = new ArrayList<>();
        do {
            columns.add(cursor.identifier("a column name"));
        } while (cursor.accept(TokenType.COMMA));
        cursor.expect(TokenType.RPAREN, "',' or ')'");
        return columns;
    }

    /** Parses {@code name}, {@code schema.name} or {@code database.schema.name}. */
    private QualifiedName qualifiedName(String description) {
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
    private String dottedName(String description) {
        StringBuilder name = new StringBuilder(Identifiers.quoteIfNeeded(cursor.identifier(description)));
        while (cursor.accept(TokenType.DOT)) {
            name.append('.').append(Identifiers.quoteIfNeeded(cursor.identifier(description)));
        }
        return name.toString();
    }

    // ---------------------------------------------------------------- table assembly

    /** Collects the parts of a table and applies the checks PostgreSQL itself would make. */
    private final class TableBuilder {

        private final List<Column> columns = new ArrayList<>();
        private final Set<String> columnNames = new HashSet<>();
        private final List<Constraint> constraints = new ArrayList<>();
        private final List<String> notNullColumns = new ArrayList<>();

        void addColumn(Column column, Token at) {
            if (!columnNames.add(column.name())) {
                throw ParseError.syntax(
                        "Column " + Identifiers.quote(column.name()) + " is specified more than once", at);
            }
            columns.add(column);
        }

        Table build(
                QualifiedName name,
                Persistence persistence,
                List<QualifiedName> inherits,
                Optional<String> partitionBy) {
            // Inherited columns are not listed in the statement, so they cannot be checked here.
            boolean allColumnsKnown = inherits.isEmpty();
            Set<String> mustBeNotNull = new HashSet<>(notNullColumns);
            if (allColumnsKnown) {
                notNullColumns.forEach(this::requireColumn);
            }

            int primaryKeys = 0;
            for (Constraint constraint : constraints) {
                List<String> referenced =
                        switch (constraint) {
                            case PrimaryKey primaryKey -> {
                                primaryKeys++;
                                // Primary key columns are implicitly NOT NULL.
                                mustBeNotNull.addAll(primaryKey.columns());
                                yield primaryKey.columns();
                            }
                            case Unique unique -> unique.columns();
                            case ForeignKey foreignKey -> foreignKey.columns();
                            case Check check -> List.<String>of();
                        };
                if (allColumnsKnown) {
                    referenced.forEach(this::requireColumn);
                }
            }
            if (primaryKeys > 1) {
                throw ParseError.syntax(
                        "Multiple primary keys for table " + name.toSql() + " are not allowed", span);
            }

            List<Column> finalColumns =
                    columns.stream()
                            .map(c -> mustBeNotNull.contains(c.name()) ? c.withNotNull(true) : c)
                            .toList();
            return new Table(name, finalColumns, constraints, persistence, inherits, partitionBy);
        }

        private void requireColumn(String columnName) {
            if (!columnNames.contains(columnName)) {
                throw ParseError.syntax(
                        "Column " + Identifiers.quote(columnName) + " named in a constraint does not exist",
                        span);
            }
        }
    }
}
