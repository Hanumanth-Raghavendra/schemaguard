package io.github.hanumanthraghavendra.schemaguard.engine.model;

import java.util.Objects;

/**
 * A schema-qualified object name. Both parts are stored exactly as PostgreSQL would store
 * them: unquoted names already folded to lower case, quoted names with their case kept.
 */
public record QualifiedName(String schema, String name) implements Comparable<QualifiedName> {

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

    /** Orders by schema, then by name, so listings come out in a stable order. */
    @Override
    public int compareTo(QualifiedName other) {
        int bySchema = schema.compareTo(other.schema);
        return bySchema != 0 ? bySchema : name.compareTo(other.name);
    }
}
