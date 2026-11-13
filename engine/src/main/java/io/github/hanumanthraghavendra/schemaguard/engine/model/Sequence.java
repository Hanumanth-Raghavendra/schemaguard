package io.github.hanumanthraghavendra.schemaguard.engine.model;

import java.util.Objects;
import java.util.Optional;

/**
 * A sequence generator.
 *
 * @param start    empty when not given; PostgreSQL then starts at the minimum (ascending) or
 *                 maximum (descending) value
 * @param minValue empty for {@code NO MINVALUE} or when not given
 * @param maxValue empty for {@code NO MAXVALUE} or when not given
 * @param ownedBy  the column whose removal also drops this sequence
 */
public record Sequence(
        QualifiedName name,
        Persistence persistence,
        Optional<DataType> dataType,
        Optional<Long> start,
        long increment,
        Optional<Long> minValue,
        Optional<Long> maxValue,
        long cache,
        boolean cycle,
        Optional<ColumnRef> ownedBy) {

    public Sequence {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(persistence, "persistence");
        Objects.requireNonNull(dataType, "dataType");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(minValue, "minValue");
        Objects.requireNonNull(maxValue, "maxValue");
        Objects.requireNonNull(ownedBy, "ownedBy");
        if (increment == 0) {
            throw new IllegalArgumentException("increment must not be zero");
        }
    }

    public Sequence withOwnedBy(Optional<ColumnRef> value) {
        return new Sequence(
                name, persistence, dataType, start, increment, minValue, maxValue, cache, cycle, value);
    }
}
