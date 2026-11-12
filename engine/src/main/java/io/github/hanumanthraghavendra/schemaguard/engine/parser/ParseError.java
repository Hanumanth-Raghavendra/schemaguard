package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic;
import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Severity;
import io.github.hanumanthraghavendra.schemaguard.engine.lexer.Token;
import io.github.hanumanthraghavendra.schemaguard.engine.source.SourceSpan;

/**
 * Internal control flow for the recursive-descent parser: thrown deep inside a statement and
 * caught once per statement, where it becomes a {@link Diagnostic}. It never leaves the
 * parser package. Stack traces are disabled because the exception is not a bug report.
 */
final class ParseError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean unsupported;
    private final transient SourceSpan span;

    private ParseError(boolean unsupported, String message, SourceSpan span) {
        super(message, null, false, false);
        this.unsupported = unsupported;
        this.span = span;
    }

    static ParseError syntax(String message, Token at) {
        return new ParseError(false, message, at.span());
    }

    static ParseError syntax(String message, SourceSpan span) {
        return new ParseError(false, message, span);
    }

    static ParseError unsupported(String feature, Token at) {
        return new ParseError(true, "Unsupported feature: " + feature, at.span());
    }

    boolean isUnsupported() {
        return unsupported;
    }

    Diagnostic toDiagnostic() {
        return unsupported
                ? new Diagnostic(Severity.WARNING, ParseErrors.UNSUPPORTED_FEATURE, getMessage(), span)
                : new Diagnostic(Severity.ERROR, ParseErrors.SYNTAX_ERROR, getMessage(), span);
    }
}
