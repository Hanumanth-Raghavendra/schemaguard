package io.github.hanumanthraghavendra.schemaguard.engine.diagnostics;

import io.github.hanumanthraghavendra.schemaguard.engine.source.SourceSpan;
import java.util.Objects;

/**
 * A problem found in the input, tied to the place it occurred.
 *
 * <p>The engine never throws on bad SQL. It reports diagnostics and keeps going, so one
 * mistake in a 5,000-line dump does not hide everything after it.
 *
 * @param code stable machine-readable identifier, for example {@code SG-LEX-001}
 */
public record Diagnostic(Severity severity, String code, String message, SourceSpan span) {

    public Diagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(span, "span");
    }
}
