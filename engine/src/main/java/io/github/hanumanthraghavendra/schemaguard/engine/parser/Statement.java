package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import io.github.hanumanthraghavendra.schemaguard.engine.model.Table;
import io.github.hanumanthraghavendra.schemaguard.engine.source.SourceSpan;
import java.util.Objects;

/**
 * One parsed SQL statement.
 *
 * <p>Every statement in the input yields exactly one of these, so nothing is dropped without
 * a trace: what the parser cannot analyse becomes {@link Unsupported} or {@link Invalid}.
 */
public sealed interface Statement {

    /** Where the statement sits in the source, excluding its terminating semicolon. */
    SourceSpan span();

    record CreateTable(Table table, boolean ifNotExists, SourceSpan span) implements Statement {
        public CreateTable {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(span, "span");
        }
    }

    /**
     * A statement that is recognised and deliberately not modelled because it does not change
     * the schema's structure: {@code SET}, {@code COMMENT}, {@code GRANT}, ownership changes.
     *
     * @param kind the statement's leading keywords, for example {@code SET}
     */
    record Ignored(String kind, SourceSpan span) implements Statement {}

    /**
     * A valid statement the parser does not handle yet. A warning diagnostic accompanies it.
     *
     * @param kind the statement's leading keywords, for example {@code CREATE FUNCTION}
     */
    record Unsupported(String kind, SourceSpan span) implements Statement {}

    /** A statement with a syntax error. An error diagnostic accompanies it. */
    record Invalid(SourceSpan span) implements Statement {}
}
