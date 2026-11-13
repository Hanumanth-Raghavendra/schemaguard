package io.github.hanumanthraghavendra.schemaguard.engine.catalog;

import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic;
import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Severity;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import java.util.List;
import java.util.Objects;

/** The schema that a script produces, and everything that went wrong on the way. */
public record CatalogResult(Catalog catalog, List<Diagnostic> diagnostics) {

    public CatalogResult {
        Objects.requireNonNull(catalog, "catalog");
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
    }
}
