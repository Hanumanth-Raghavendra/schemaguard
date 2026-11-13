package io.github.hanumanthraghavendra.schemaguard.engine.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An index. It lives in the same schema as its table, so its name is never qualified.
 *
 * @param name      empty when the statement leaves the name for PostgreSQL to choose
 * @param method    the access method in lower case, {@code btree} unless stated
 * @param predicate the {@code WHERE} condition of a partial index
 */
public record Index(
        Optional<String> name,
        QualifiedName table,
        boolean unique,
        String method,
        List<IndexColumn> columns,
        List<String> include,
        boolean nullsNotDistinct,
        Optional<Expression> predicate) {

    public Index {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(method, "method");
        columns = List.copyOf(columns);
        include = List.copyOf(include);
        Objects.requireNonNull(predicate, "predicate");
    }
}
