package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import java.util.Objects;

/**
 * @param defaultSchema the schema given to names written without one. PostgreSQL resolves
 *                      these through {@code search_path}, which defaults to {@code public}.
 */
public record ParserOptions(String defaultSchema) {

    public ParserOptions {
        Objects.requireNonNull(defaultSchema, "defaultSchema");
    }

    public static ParserOptions defaults() {
        return new ParserOptions("public");
    }
}
