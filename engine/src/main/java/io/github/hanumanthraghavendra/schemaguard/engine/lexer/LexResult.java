package io.github.hanumanthraghavendra.schemaguard.engine.lexer;

import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic;
import java.util.List;

/**
 * The outcome of lexing: every token found, always ending with {@link TokenType#EOF},
 * and every problem met along the way. Both lists are immutable.
 */
public record LexResult(List<Token> tokens, List<Diagnostic> diagnostics) {

    public LexResult {
        tokens = List.copyOf(tokens);
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean hasErrors() {
        return !diagnostics.isEmpty();
    }
}
