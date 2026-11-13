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

    public Optional<Constraint> constraint(String constraintName) {
        return constraints.stream()
                .filter(c -> c.name().equals(Optional.of(constraintName)))
                .findFirst();
    }

    public Table withName(QualifiedName value) {
        return new Table(value, columns, constraints, persistence, inherits, partitionBy);
    }

    public Table withColumns(List<Column> value) {
        return new Table(name, value, constraints, persistence, inherits, partitionBy);
    }

    public Table withConstraints(List<Constraint> value) {
        return new Table(name, columns, value, persistence, inherits, partitionBy);
    }
}
