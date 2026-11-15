package io.github.hanumanthraghavendra.schemaguard.engine.catalog;

import io.github.hanumanthraghavendra.schemaguard.engine.model.DataType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Sequence;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.Statement;
import java.util.Optional;

/**
 * Fills in the values PostgreSQL assumes for a sequence, so that a sequence compares equal
 * however much of it was spelled out. {@code pg_dump}, for example, prints {@code NO MAXVALUE}
 * for a descending sequence whose maximum is -1, because -1 is the default.
 */
final class SequenceDefaults {

    private SequenceDefaults() {}

    /** The smallest and largest value of a sequence type. */
    private record Range(long min, long max) {
        static Range of(String type) {
            return switch (type) {
                case "smallint" -> new Range(Short.MIN_VALUE, Short.MAX_VALUE);
                case "integer" -> new Range(Integer.MIN_VALUE, Integer.MAX_VALUE);
                case "bigint" -> new Range(Long.MIN_VALUE, Long.MAX_VALUE);
                default -> throw CatalogError.invalid("sequence type must be smallint, integer, or bigint");
            };
        }
    }

    static Sequence resolve(Sequence sequence) {
        String type = sequence.dataType().map(DataType::toSql).orElse("bigint");
        Range range = Range.of(type);
        boolean ascending = sequence.increment() > 0;
        long max = sequence.maxValue().orElse(ascending ? range.max() : -1);
        long min = sequence.minValue().orElse(ascending ? 1 : range.min());
        long start = sequence.start().orElse(ascending ? min : max);
        return checked(
                new Sequence(
                        sequence.name(),
                        sequence.persistence(),
                        Optional.of(DataType.of(type)),
                        Optional.of(start),
                        sequence.increment(),
                        Optional.of(min),
                        Optional.of(max),
                        sequence.cache(),
                        sequence.cycle(),
                        sequence.ownedBy()));
    }

    /**
     * Applies {@code ALTER SEQUENCE} options to a sequence whose settings are all resolved.
     *
     * <p>A new type moves a limit only if the limit was the old type's own: a sequence that ran
     * up to the largest {@code bigint} runs up to the largest {@code smallint} afterwards, while
     * {@code MAXVALUE 100} stays 100. A new direction alone moves nothing. All of this is what
     * PostgreSQL 16 was observed to do.
     */
    static Sequence alter(Sequence current, Statement.AlterSequence change) {
        String oldType = current.dataType().orElseThrow().toSql();
        String type = change.dataType().map(DataType::toSql).orElse(oldType);
        Range old = Range.of(oldType);
        Range range = Range.of(type);
        long increment = change.increment().orElse(current.increment());
        boolean typeChanged = !type.equals(oldType);

        long max = current.maxValue().orElseThrow();
        boolean resetMax = typeChanged && max == old.max();
        if (change.maxValue().isPresent() && change.maxValue().get().isPresent()) {
            max = change.maxValue().get().get();
        } else if (change.maxValue().isPresent() || resetMax) {
            max = increment > 0 || resetMax ? range.max() : -1;
        }
        long min = current.minValue().orElseThrow();
        boolean resetMin = typeChanged && min == old.min();
        if (change.minValue().isPresent() && change.minValue().get().isPresent()) {
            min = change.minValue().get().get();
        } else if (change.minValue().isPresent() || resetMin) {
            min = increment < 0 || resetMin ? range.min() : 1;
        }
        return checked(
                new Sequence(
                        current.name(),
                        current.persistence(),
                        Optional.of(DataType.of(type)),
                        Optional.of(change.start().orElse(current.start().orElseThrow())),
                        increment,
                        Optional.of(min),
                        Optional.of(max),
                        change.cache().orElse(current.cache()),
                        change.cycle().orElse(current.cycle()),
                        change.ownedBy().orElse(current.ownedBy())));
    }

    /** The checks PostgreSQL makes on the settings of a sequence, with its messages. */
    private static Sequence checked(Sequence sequence) {
        String type = sequence.dataType().orElseThrow().toSql();
        Range range = Range.of(type);
        long min = sequence.minValue().orElseThrow();
        long max = sequence.maxValue().orElseThrow();
        long start = sequence.start().orElseThrow();
        if (max < range.min() || max > range.max()) {
            throw CatalogError.invalid("MAXVALUE (" + max + ") is out of range for sequence data type " + type);
        }
        if (min < range.min() || min > range.max()) {
            throw CatalogError.invalid("MINVALUE (" + min + ") is out of range for sequence data type " + type);
        }
        if (min >= max) {
            throw CatalogError.invalid("MINVALUE (" + min + ") must be less than MAXVALUE (" + max + ")");
        }
        if (start < min) {
            throw CatalogError.invalid("START value (" + start + ") cannot be less than MINVALUE (" + min + ")");
        }
        if (start > max) {
            throw CatalogError.invalid("START value (" + start + ") cannot be greater than MAXVALUE (" + max + ")");
        }
        if (sequence.cache() <= 0) {
            throw CatalogError.invalid("CACHE (" + sequence.cache() + ") must be greater than zero");
        }
        return sequence;
    }
}
