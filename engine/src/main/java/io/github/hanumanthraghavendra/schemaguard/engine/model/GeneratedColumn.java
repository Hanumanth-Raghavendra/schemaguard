package io.github.hanumanthraghavendra.schemaguard.engine.model;

import java.util.Objects;

/** The definition of a {@code GENERATED ALWAYS AS (expression)} column. */
public record GeneratedColumn(Expression expression, boolean stored) {

    public GeneratedColumn {
        Objects.requireNonNull(expression, "expression");
    }
}
