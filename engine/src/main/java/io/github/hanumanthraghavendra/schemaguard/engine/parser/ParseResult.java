package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic;
import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Severity;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Table;
import java.util.List;

/** Every statement found, in source order, plus all lexer and parser diagnostics. */
public record ParseResult(List<Statement> statements, List<Diagnostic> diagnostics) {

    public ParseResult {
        statements = List.copyOf(statements);
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
    }

    /** The tables defined by {@code CREATE TABLE} statements, in source order. */
    public List<Table> tables() {
        return statements.stream()
                .filter(Statement.CreateTable.class::isInstance)
                .map(s -> ((Statement.CreateTable) s).table())
                .toList();
    }
}
