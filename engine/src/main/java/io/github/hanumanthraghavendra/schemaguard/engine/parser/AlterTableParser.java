package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import io.github.hanumanthraghavendra.schemaguard.engine.lexer.Token;
import io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Column;
import io.github.hanumanthraghavendra.schemaguard.engine.model.DataType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Deferrability;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Expression;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Identity;
import io.github.hanumanthraghavendra.schemaguard.engine.model.PrimaryKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Parses {@code ALTER TABLE} into its list of {@link AlterAction}s. */
final class AlterTableParser extends ParserBase {

    private final TableElementParser elements;

    AlterTableParser(ParserBase shared, TableElementParser elements) {
        super(shared);
        this.elements = elements;
    }

    Statement parse() {
        cursor.expectWord("alter");
        cursor.expectWord("table");
        boolean ifExists = ifExists();
        boolean only = cursor.acceptWord("only");
        QualifiedName table = qualifiedName("a table name");
        if (cursor.peek().isOperator("*")) {
            cursor.next();
        }
        List<AlterAction> actions = new ArrayList<>();
        do {
            actions.add(action());
        } while (cursor.accept(TokenType.COMMA));
        expectEndOfStatement();
        return new Statement.AlterTable(table, ifExists, only, actions, span);
    }

    private AlterAction action() {
        Token start = cursor.peek();
        if (cursor.acceptWord("add")) {
            return add();
        }
        if (cursor.acceptWord("drop")) {
            return drop();
        }
        if (cursor.acceptWord("alter")) {
            return alterColumn();
        }
        if (cursor.acceptWords("validate", "constraint")) {
            return new AlterAction.ValidateConstraint(cursor.identifier("a constraint name"));
        }
        if (cursor.acceptWord("rename")) {
            return rename();
        }
        if (cursor.acceptWords("set", "schema")) {
            return new AlterAction.SetSchema(cursor.identifier("a schema name"));
        }
        if (start.type() == TokenType.WORD) {
            throw ParseError.unsupported("ALTER TABLE ... " + leadingWords(), start);
        }
        throw cursor.expected("an ALTER TABLE action");
    }

    // ---------------------------------------------------------------- ADD

    private AlterAction add() {
        if (isConstraintUsingIndex()) {
            return addConstraintUsingIndex();
        }
        if (!cursor.isWord("column") && elements.startsTableConstraint()) {
            TableElementParser.TableBuilder holder = elements.newTable();
            elements.parseTableConstraint(holder);
            // PostgreSQL 18 spells SET NOT NULL as a constraint too: ADD NOT NULL column.
            return holder.notNullColumns.isEmpty()
                    ? new AlterAction.AddConstraint(holder.constraints.get(0))
                    : new AlterAction.SetNotNull(holder.notNullColumns.get(0));
        }
        cursor.acceptWord("column");
        boolean ifNotExists = ifNotExists();
        TableElementParser.TableBuilder holder = elements.newTable();
        elements.parseColumn(holder);
        Column column = holder.columns.get(0);
        boolean primaryKey = holder.constraints.stream().anyMatch(PrimaryKey.class::isInstance);
        return new AlterAction.AddColumn(
                primaryKey ? column.withNotNull(true) : column, holder.constraints, ifNotExists);
    }

    /** Looks ahead for {@code [CONSTRAINT name] UNIQUE | PRIMARY KEY USING INDEX index}. */
    private boolean isConstraintUsingIndex() {
        int i = cursor.isWord("constraint") ? 2 : 0;
        int afterKind;
        if (cursor.isWordAt(i, "unique")) {
            afterKind = i + 1;
        } else if (cursor.isWordAt(i, "primary") && cursor.isWordAt(i + 1, "key")) {
            afterKind = i + 2;
        } else {
            return false;
        }
        return cursor.isWordAt(afterKind, "using")
                && cursor.isWordAt(afterKind + 1, "index")
                && !cursor.isWordAt(afterKind + 2, "tablespace");
    }

    private AlterAction addConstraintUsingIndex() {
        Optional<String> name = Optional.empty();
        if (cursor.acceptWord("constraint")) {
            name = Optional.of(cursor.identifier("a constraint name"));
        }
        boolean primaryKey = !cursor.acceptWord("unique");
        if (primaryKey) {
            cursor.acceptWords("primary", "key");
        }
        cursor.acceptWords("using", "index");
        String index = cursor.identifier("an index name");

        boolean deferrable = cursor.acceptWord("deferrable");
        if (!deferrable) {
            cursor.acceptWords("not", "deferrable");
        }
        Deferrability deferrability;
        if (cursor.acceptWords("initially", "deferred")) {
            deferrability = Deferrability.DEFERRABLE_INITIALLY_DEFERRED;
        } else {
            cursor.acceptWords("initially", "immediate");
            deferrability =
                    deferrable
                            ? Deferrability.DEFERRABLE_INITIALLY_IMMEDIATE
                            : Deferrability.NOT_DEFERRABLE;
        }
        return new AlterAction.AddConstraintUsingIndex(name, primaryKey, index, deferrability);
    }

    // ---------------------------------------------------------------- DROP, RENAME

    private AlterAction drop() {
        if (cursor.acceptWord("constraint")) {
            boolean ifExists = ifExists();
            String name = cursor.identifier("a constraint name");
            return new AlterAction.DropConstraint(name, ifExists, cascade());
        }
        cursor.acceptWord("column");
        boolean ifExists = ifExists();
        String name = cursor.identifier("a column name");
        return new AlterAction.DropColumn(name, ifExists, cascade());
    }

    private AlterAction rename() {
        if (cursor.acceptWord("constraint")) {
            String from = cursor.identifier("a constraint name");
            cursor.expectWord("to");
            return new AlterAction.RenameConstraint(from, cursor.identifier("a constraint name"));
        }
        if (cursor.acceptWord("to")) {
            return new AlterAction.RenameTable(cursor.identifier("a table name"));
        }
        cursor.acceptWord("column");
        String from = cursor.identifier("a column name");
        cursor.expectWord("to");
        return new AlterAction.RenameColumn(from, cursor.identifier("a column name"));
    }

    // ---------------------------------------------------------------- ALTER COLUMN

    private AlterAction alterColumn() {
        if (cursor.isWord("constraint")) {
            throw ParseError.unsupported("ALTER TABLE ... ALTER CONSTRAINT", cursor.peek());
        }
        cursor.acceptWord("column");
        String column = cursor.identifier("a column name");
        Token start = cursor.peek();

        if (cursor.acceptWords("set", "data", "type") || cursor.acceptWord("type")) {
            DataType type = TypeParser.parse(cursor);
            Optional<String> collation = Optional.empty();
            if (cursor.acceptWord("collate")) {
                collation = Optional.of(dottedName("a collation name"));
            }
            Optional<Expression> using = Optional.empty();
            if (cursor.acceptWord("using")) {
                using = Optional.of(expressionUntilComma("an expression after USING"));
            }
            return new AlterAction.AlterColumnType(column, type, collation, using);
        }
        if (cursor.acceptWords("set", "default")) {
            return new AlterAction.SetDefault(
                    column, expressionUntilComma("an expression after DEFAULT"));
        }
        if (cursor.acceptWords("drop", "default")) {
            return new AlterAction.DropDefault(column);
        }
        if (cursor.acceptWords("set", "not", "null")) {
            return new AlterAction.SetNotNull(column);
        }
        if (cursor.acceptWords("drop", "not", "null")) {
            return new AlterAction.DropNotNull(column);
        }
        if (cursor.acceptWords("add", "generated")) {
            boolean always = cursor.acceptWord("always");
            if (!always && !cursor.acceptWords("by", "default")) {
                throw cursor.expected("ALWAYS or BY DEFAULT");
            }
            cursor.expectWord("as");
            cursor.expectWord("identity");
            if (cursor.is(TokenType.LPAREN)) {
                cursor.balancedParentheses();
            }
            return new AlterAction.AddIdentity(column, always ? Identity.ALWAYS : Identity.BY_DEFAULT);
        }
        if (cursor.acceptWords("drop", "identity")) {
            return new AlterAction.DropIdentity(column, ifExists());
        }
        if (start.type() == TokenType.WORD) {
            throw ParseError.unsupported("ALTER TABLE ... ALTER COLUMN " + leadingWords(), start);
        }
        throw cursor.expected("a column action");
    }

    /** Up to two keywords at the cursor, in upper case, to name an action that is not handled. */
    private String leadingWords() {
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 2 && cursor.peek(i).type() == TokenType.WORD; i++) {
            words.append(i == 0 ? "" : " ").append(cursor.peek(i).value().toUpperCase(Locale.ROOT));
        }
        return words.toString();
    }
}
