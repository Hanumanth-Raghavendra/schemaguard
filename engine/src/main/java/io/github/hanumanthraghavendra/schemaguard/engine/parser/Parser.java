package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic;
import io.github.hanumanthraghavendra.schemaguard.engine.lexer.LexResult;
import io.github.hanumanthraghavendra.schemaguard.engine.lexer.Lexer;
import io.github.hanumanthraghavendra.schemaguard.engine.lexer.Token;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parses PostgreSQL DDL into {@link Statement}s.
 *
 * <p>This is a hand-written recursive-descent parser: each grammar rule is one method, and the
 * call stack mirrors the structure of the SQL. It never throws on bad input. Each statement is
 * parsed on its own, so an error in one does not affect the next.
 */
public final class Parser {

    private Parser() {}

    public static ParseResult parse(String sql) {
        return parse(sql, ParserOptions.defaults());
    }

    public static ParseResult parse(String sql, ParserOptions options) {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(options, "options");

        LexResult lexed = Lexer.tokenize(sql);
        List<Diagnostic> diagnostics = new ArrayList<>(lexed.diagnostics());
        List<Statement> statements = new ArrayList<>();
        for (List<Token> tokens : StatementSplitter.split(lexed.tokens())) {
            statements.add(new StatementParser(tokens, options, diagnostics).parse());
        }
        return new ParseResult(statements, diagnostics);
    }
}
