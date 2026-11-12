package io.github.hanumanthraghavendra.schemaguard.engine.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A table definition.
 *
 * @param partitionBy the partitioning clause in canonical text, for example
 *                    {@code range (created_at)}, when the table is partitioned
 */
public record Table(
        QualifiedName name,
        List<Column> columns,
        List<Constraint> constraints,
        Persistence persistence,
        List<QualifiedName> inherits,
        Optional<String> partitionBy) {

    public Table {
        Objects.requireNonNull(name, "name");
        columns = List.copyOf(columns);
        constraints = List.copyOf(constraints);
        Objects.requireNonNull(persistence, "persistence");
        inherits = List.copyOf(inherits);
        Objects.requireNonNull(partitionBy, "partitionBy");
    }

    public Optional<Column> column(String columnName) {
        return columns.stream().filter(c -> c.name().equals(columnName)).findFirst();
    }

    public Optional<PrimaryKey> primaryKey() {
        return constraints.stream()
                .filter(PrimaryKey.class::isInstance)
                .map(PrimaryKey.class::cast)
                .findFirst();
    }
}
