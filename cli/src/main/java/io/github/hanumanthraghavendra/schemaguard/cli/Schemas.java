package io.github.hanumanthraghavendra.schemaguard.cli;

import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogBuilder;
import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogResult;
import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic;
import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Severity;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserOptions;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Reads schemas the way the API does: errors stop the analysis, warnings go along with it. */
final class Schemas {

    private Schemas() {}

    /** A schema and the warnings about it, in the API's form. */
    record Parsed(Catalog catalog, List<Documents.Diagnostic> warnings) {}

    /**
     * Builds the schema a SQL source describes. Warnings (statements the engine skips) are
     * printed to {@code err} in the compiler form {@code file:line:column: warning: message}.
     *
     * @param role which input it is, as the API names it: before, after or schema
     * @throws InputException if PostgreSQL would refuse the SQL; the message lists every error
     */
    static Parsed parse(String role, Source source, PrintWriter err) {
        CatalogResult result = CatalogBuilder.fromSql(source.sql(), ParserOptions.defaults());
        if (result.hasErrors()) {
            List<Diagnostic> errors =
                    result.diagnostics().stream().filter(d -> d.severity() == Severity.ERROR).toList();
            throw new InputException("The " + role + " schema (" + source.name() + ") has " + errors.size()
                    + (errors.size() == 1 ? " error" : " errors") + " and cannot be analysed:\n"
                    + errors.stream().map(d -> line(source.name(), d)).collect(Collectors.joining("\n")));
        }
        result.diagnostics().forEach(d -> err.println(line(source.name(), d)));
        return new Parsed(
                result.catalog(),
                result.diagnostics().stream().map(d -> Documents.diagnostic(role, d)).toList());
    }

    /** The warnings of both inputs, before first, as the API lists them. */
    static List<Documents.Diagnostic> warnings(Parsed before, Parsed after) {
        return Stream.concat(before.warnings().stream(), after.warnings().stream()).toList();
    }

    /** {@code before.sql:3:7: error: message [SG-CAT-012]}, which editors and CI annotations link. */
    static String line(String file, Diagnostic diagnostic) {
        return file + ":" + diagnostic.span().line() + ":" + diagnostic.span().column() + ": "
                + diagnostic.severity().name().toLowerCase(Locale.ROOT) + ": " + diagnostic.message()
                + " [" + diagnostic.code() + "]";
    }
}
