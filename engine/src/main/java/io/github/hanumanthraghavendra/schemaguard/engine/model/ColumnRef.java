package io.github.hanumanthraghavendra.schemaguard.engine.model;

import java.util.Objects;

/** A reference to one column of a table. */
public record ColumnRef(QualifiedName table, String column) {

    public ColumnRef {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(column, "column");
    }
}
