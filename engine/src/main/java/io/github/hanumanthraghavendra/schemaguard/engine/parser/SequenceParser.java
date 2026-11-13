package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import io.github.hanumanthraghavendra.schemaguard.engine.lexer.Token;
import io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ColumnRef;
import io.github.hanumanthraghavendra.schemaguard.engine.model.DataType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Persistence;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Sequence;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Parses {@code CREATE SEQUENCE} and {@code ALTER SEQUENCE ... OWNED BY}. */
final class SequenceParser extends ParserBase {

    private static final String OTHER_ALTER_FORMS = "ALTER SEQUENCE options other than OWNED BY";

    SequenceParser(ParserBase shared) {
        super(shared);
    }

    Statement parseCreate() {
        cursor.expectWord("create");
        Persistence persistence = Persistence.PERMANENT;
        if (cursor.acceptWord("temporary") || cursor.acceptWord("temp")) {
            persistence = Persistence.TEMPORARY;
        } else if (cursor.acceptWord("unlogged")) {
            persistence = Persistence.UNLOGGED;
        }
        cursor.expectWord("sequence");
        boolean ifNotExists = ifNotExists();
        QualifiedName name = qualifiedName("a sequence name");

        Optional<DataType> dataType = Optional.empty();
        Optional<Long> start = Optional.empty();
        Optional<Long> minValue = Optional.empty();
        Optional<Long> maxValue = Optional.empty();
        Optional<ColumnRef> ownedBy = Optional.empty();
        long increment = 1;
        long cache = 1;
        boolean cycle = false;

        // The options may come in any order.
        while (!cursor.atEnd()) {
            if (cursor.acceptWord("as")) {
                dataType = Optional.of(TypeParser.parse(cursor));
            } else if (cursor.acceptWord("increment")) {
                cursor.acceptWord("by");
                Token at = cursor.peek();
                increment = signedInteger();
                if (increment == 0) {
                    throw ParseError.syntax("INCREMENT must not be zero", at);
                }
            } else if (cursor.acceptWord("minvalue")) {
                minValue = Optional.of(signedInteger());
            } else if (cursor.acceptWord("maxvalue")) {
                maxValue = Optional.of(signedInteger());
            } else if (cursor.acceptWords("no", "minvalue")) {
                minValue = Optional.empty();
            } else if (cursor.acceptWords("no", "maxvalue")) {
                maxValue = Optional.empty();
            } else if (cursor.acceptWord("start")) {
                cursor.acceptWord("with");
                start = Optional.of(signedInteger());
            } else if (cursor.acceptWord("cache")) {
                cache = signedInteger();
            } else if (cursor.acceptWord("cycle")) {
                cycle = true;
            } else if (cursor.acceptWords("no", "cycle")) {
                cycle = false;
            } else if (cursor.acceptWords("owned", "by")) {
                ownedBy = ownedBy();
            } else {
                throw ParseError.syntax(
                        "Unexpected " + TokenCursor.describe(cursor.peek()) + " in the sequence definition",
                        cursor.peek());
            }
        }

        Sequence sequence =
                new Sequence(
                        name, persistence, dataType, start, increment, minValue, maxValue, cache,
                        cycle, ownedBy);
        return new Statement.CreateSequence(sequence, ifNotExists, span);
    }

    Statement parseAlter() {
        cursor.expectWord("alter");
        cursor.expectWord("sequence");
        boolean ifExists = ifExists();
        QualifiedName name = qualifiedName("a sequence name");
        if (!cursor.acceptWords("owned", "by")) {
            throw ParseError.unsupported(OTHER_ALTER_FORMS, cursor.peek());
        }
        Optional<ColumnRef> owner = ownedBy();
        if (!cursor.atEnd()) {
            throw ParseError.unsupported(OTHER_ALTER_FORMS, cursor.peek());
        }
        return new Statement.AlterSequenceOwner(name, ifExists, owner, span);
    }

    /** Parses what follows OWNED BY: {@code NONE}, or a column as {@code [schema.]table.column}. */
    private Optional<ColumnRef> ownedBy() {
        if (cursor.isWord("none") && cursor.peek(1).type() != TokenType.DOT) {
            cursor.next();
            return Optional.empty();
        }
        Token start = cursor.peek();
        List<String> parts = new ArrayList<>();
        parts.add(cursor.identifier("a table name"));
        while (cursor.accept(TokenType.DOT)) {
            parts.add(cursor.identifier("a name"));
        }
        int n = parts.size();
        if (n < 2 || n > 4) {
            throw ParseError.syntax(
                    "OWNED BY needs a column written as table.column or schema.table.column", start);
        }
        String schema = n >= 3 ? parts.get(n - 3) : options.defaultSchema();
        return Optional.of(new ColumnRef(new QualifiedName(schema, parts.get(n - 2)), parts.get(n - 1)));
    }
}
