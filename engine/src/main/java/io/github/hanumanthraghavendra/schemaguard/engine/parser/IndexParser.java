package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import io.github.hanumanthraghavendra.schemaguard.engine.lexer.Token;
import io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Expression;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Index;
import io.github.hanumanthraghavendra.schemaguard.engine.model.IndexColumn;
import io.github.hanumanthraghavendra.schemaguard.engine.model.NullsOrder;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.model.SortOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Parses {@code CREATE INDEX}. */
final class IndexParser extends ParserBase {

    /** Words that can follow an index key and are therefore not an operator class name. */
    private static final Set<String> ORDER_KEYWORDS = Set.of("asc", "desc", "nulls");

    IndexParser(ParserBase shared) {
        super(shared);
    }

    Statement parse() {
        cursor.expectWord("create");
        boolean unique = cursor.acceptWord("unique");
        cursor.expectWord("index");
        boolean concurrently = cursor.acceptWord("concurrently");
        boolean ifNotExists = ifNotExists();
        // The name is optional, and ON is reserved, so "ON" here cannot be a name.
        Optional<String> name = Optional.empty();
        if (ifNotExists || !cursor.isWord("on")) {
            name = Optional.of(cursor.identifier("an index name"));
        }
        cursor.expectWord("on");
        boolean only = cursor.acceptWord("only");
        QualifiedName table = qualifiedName("a table name");
        String method = cursor.acceptWord("using") ? cursor.identifier("an index method") : "btree";

        cursor.expect(TokenType.LPAREN, "'('");
        List<IndexColumn> columns = new ArrayList<>();
        do {
            columns.add(key());
        } while (cursor.accept(TokenType.COMMA));
        cursor.expect(TokenType.RPAREN, "',' or ')'");

        List<String> include = cursor.acceptWord("include") ? columnList() : List.of();
        boolean nullsNotDistinct = false;
        if (cursor.acceptWord("nulls")) {
            nullsNotDistinct = cursor.acceptWord("not");
            cursor.expectWord("distinct");
        }
        if (cursor.acceptWord("with")) {
            cursor.balancedParentheses();
        }
        if (cursor.acceptWord("tablespace")) {
            cursor.identifier("a tablespace name");
        }
        Optional<Expression> predicate = Optional.empty();
        if (cursor.acceptWord("where")) {
            predicate = Optional.of(remainingExpression("a condition after WHERE"));
        }
        expectEndOfStatement();

        Index index =
                new Index(name, table, unique, method, columns, include, nullsNotDistinct, predicate);
        return new Statement.CreateIndex(index, concurrently, ifNotExists, only, span);
    }

    /**
     * Parses one index key: a column, a function call such as {@code lower(email)}, or any
     * expression in parentheses, followed by its optional collation, operator class and ordering.
     */
    private IndexColumn key() {
        Optional<String> column = Optional.empty();
        Optional<Expression> expression = Optional.empty();
        if (cursor.is(TokenType.LPAREN)) {
            expression = Optional.of(parenthesizedExpression());
        } else {
            int from = cursor.position();
            Token first = cursor.peek();
            String name = cursor.identifier("a column name or expression");
            while (cursor.accept(TokenType.DOT)) {
                cursor.identifier("a name");
            }
            if (cursor.is(TokenType.LPAREN)) {
                cursor.balancedParentheses();
                expression =
                        Optional.of(
                                ExpressionRenderer.toExpression(cursor.slice(from, cursor.position())));
            } else if (cursor.position() - from > 1) {
                throw ParseError.syntax("An index column cannot be a qualified name", first);
            } else {
                column = Optional.of(name);
            }
        }

        Optional<String> collation = Optional.empty();
        if (cursor.acceptWord("collate")) {
            collation = Optional.of(dottedName("a collation name"));
        }

        Optional<String> operatorClass = Optional.empty();
        Token next = cursor.peek();
        boolean nameFollows =
                next.type() == TokenType.QUOTED_IDENTIFIER
                        || (next.type() == TokenType.WORD && !ORDER_KEYWORDS.contains(next.value()));
        if (nameFollows) {
            String operatorClassName = dottedName("an operator class");
            if (cursor.is(TokenType.LPAREN)) {
                operatorClassName += "(" + ExpressionRenderer.render(cursor.balancedParentheses()) + ")";
            }
            operatorClass = Optional.of(operatorClassName);
        }

        SortOrder order = SortOrder.ASC;
        if (cursor.acceptWord("desc")) {
            order = SortOrder.DESC;
        } else {
            cursor.acceptWord("asc");
        }
        // PostgreSQL's default: NULLs sort as if larger than everything else.
        NullsOrder nulls = order == SortOrder.DESC ? NullsOrder.FIRST : NullsOrder.LAST;
        if (cursor.acceptWord("nulls")) {
            if (cursor.acceptWord("first")) {
                nulls = NullsOrder.FIRST;
            } else if (cursor.acceptWord("last")) {
                nulls = NullsOrder.LAST;
            } else {
                throw cursor.expected("FIRST or LAST");
            }
        }
        return new IndexColumn(column, expression, collation, operatorClass, order, nulls);
    }
}
