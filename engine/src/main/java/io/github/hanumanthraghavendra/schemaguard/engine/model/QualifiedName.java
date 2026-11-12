package io.github.hanumanthraghavendra.schemaguard.engine.model;

import java.util.Objects;

/**
 * A schema-qualified object name. Both parts are stored exactly as PostgreSQL would store
 * them: unquoted names already folded to lower case, quoted names with their case kept.
 */
public record QualifiedName(String schema, String name) {

    public QualifiedName {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(name, "name");
    }

    /** The name as it must be written in SQL, quoted only where required. */
    public String toSql() {
        return Identifiers.quoteIfNeeded(schema) + "." + Identifiers.quoteIfNeeded(name);
    }

    @Override
    public String toString() {
        return toSql();
    }
}
