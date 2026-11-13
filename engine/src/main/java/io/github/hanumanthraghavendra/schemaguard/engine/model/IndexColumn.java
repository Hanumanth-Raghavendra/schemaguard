package io.github.hanumanthraghavendra.schemaguard.engine.model;

import java.util.Objects;
import java.util.Optional;

/**
 * One key of an index: either a plain column or an expression, never both.
 *
 * <p>The sort settings are always stored explicitly, with PostgreSQL's defaults filled in
 * (ascending, and NULLs last when ascending or first when descending), so two indexes that
 * differ only in how much was spelled out compare equal.
 */
public record IndexColumn(
        Optional<String> column,
        Optional<Expression> expression,
        Optional<String> collation,
        Optional<String> operatorClass,
        SortOrder order,
        NullsOrder nulls) {

    public IndexColumn {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(collation, "collation");
        Objects.requireNonNull(operatorClass, "operatorClass");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(nulls, "nulls");
        if (column.isPresent() == expression.isPresent()) {
            throw new IllegalArgumentException("An index key is either a column or an expression");
        }
    }

    /** An ascending key over a plain column with default settings. */
    public static IndexColumn ofColumn(String name) {
        return new IndexColumn(
                Optional.of(name),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                SortOrder.ASC,
                NullsOrder.LAST);
    }
}
