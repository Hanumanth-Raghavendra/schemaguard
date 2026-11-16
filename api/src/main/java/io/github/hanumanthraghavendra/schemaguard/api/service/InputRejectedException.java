package io.github.hanumanthraghavendra.schemaguard.api.service;

import io.github.hanumanthraghavendra.schemaguard.api.dto.Responses;
import java.util.List;

/** An input that cannot be analysed: it does not parse, or PostgreSQL would refuse it. Answered with 422. */
public class InputRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<Responses.Diagnostic> diagnostics;

    public InputRejectedException(String message, List<Responses.Diagnostic> diagnostics) {
        super(message);
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<Responses.Diagnostic> diagnostics() {
        return diagnostics;
    }
}
