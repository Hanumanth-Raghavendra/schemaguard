package io.github.hanumanthraghavendra.schemaguard.engine.catalog;

import io.github.hanumanthraghavendra.schemaguard.engine.model.DataType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Sequence;
import java.util.Optional;

/**
 * Fills in the values PostgreSQL assumes for a sequence, so that a sequence compares equal
 * however much of it was spelled out. {@code pg_dump}, for example, prints {@code NO MAXVALUE}
 * for a descending sequence whose maximum is -1, because -1 is the default.
 */
final class SequenceDefaults {

    private SequenceDefaults() {}

    static Sequence resolve(Sequence sequence) {
        String type = sequence.dataType().map(DataType::toSql).orElse("bigint");
        long typeMin;
        long typeMax;
        switch (type) {
            case "smallint" -> {
                typeMin = Short.MIN_VALUE;
                typeMax = Short.MAX_VALUE;
            }
            case "integer" -> {
                typeMin = Integer.MIN_VALUE;
                typeMax = Integer.MAX_VALUE;
            }
            case "bigint" -> {
                typeMin = Long.MIN_VALUE;
                typeMax = Long.MAX_VALUE;
            }
            default -> throw CatalogError.invalid("sequence type must be smallint, integer, or bigint");
        }
        boolean ascending = sequence.increment() > 0;
        long max = sequence.maxValue().orElse(ascending ? typeMax : -1);
        long min = sequence.minValue().orElse(ascending ? 1 : typeMin);
        long start = sequence.start().orElse(ascending ? min : max);
        return new Sequence(
                sequence.name(),
                sequence.persistence(),
                Optional.of(DataType.of(type)),
                Optional.of(start),
                sequence.increment(),
                Optional.of(min),
                Optional.of(max),
                sequence.cache(),
                sequence.cycle(),
                sequence.ownedBy());
    }
}
