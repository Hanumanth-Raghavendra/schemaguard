package io.github.hanumanthraghavendra.schemaguard.engine.rules;

import io.github.hanumanthraghavendra.schemaguard.engine.model.DataType;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * What changing a column from one type to another means for the table and for the values in it.
 *
 * <p>Two separate questions are answered here:
 *
 * <ul>
 *   <li>{@link #rewrites()}: does PostgreSQL have to write the whole table again? It does not
 *       when the stored bytes stay valid for the new type, as for {@code varchar(10)} to
 *       {@code varchar(20)}. The answers are checked against measurements on a real server.
 *   <li>{@link #kind()}: can every existing value be represented in the new type?
 * </ul>
 *
 * <p>The two are independent: {@code integer} to {@code bigint} keeps every value and still
 * rewrites the table.
 */
public record TypeChange(DataType from, DataType to) {

    /** What happens to the existing values. */
    public enum Kind {
        /** Every existing value fits the new type unchanged. */
        WIDENING,
        /** The new type holds less: values may be rounded or cut, or the change may fail. */
        NARROWING,
        /** The types are of different families; each value has to be converted, which can fail. */
        CONVERSION
    }

    private static final String VARCHAR = "character varying";
    private static final String CHAR = "character";
    private static final String TEXT = "text";
    private static final String NUMERIC = "numeric";
    private static final String VARBIT = "bit varying";

    private static final Set<String> STRINGS = Set.of(VARCHAR, CHAR, TEXT);
    private static final Map<String, Integer> INTEGERS = Map.of("smallint", 1, "integer", 2, "bigint", 3);
    private static final Set<String> FLOATS = Set.of("real", "double precision");

    /** Types whose only modifier is the number of fractional digits kept for seconds. */
    private static final Set<String> TIMES =
            Set.of(
                    "timestamp without time zone",
                    "timestamp with time zone",
                    "time without time zone",
                    "time with time zone",
                    "interval");

    private static final Set<String> TIMESTAMPS = Set.of("timestamp without time zone", "timestamp with time zone");

    private static final int UNLIMITED = Integer.MAX_VALUE;

    /**
     * True if {@code ALTER COLUMN ... TYPE} has to rewrite the table. Anything not known to be
     * safe counts as a rewrite.
     *
     * <p>One case depends on the session: {@code timestamp} to {@code timestamptz} skips the
     * rewrite only when the session time zone is UTC. It is reported as a rewrite.
     */
    public boolean rewrites() {
        if (from.equals(to)) {
            return false;
        }
        if (!plain(from) || !plain(to)) {
            return true;
        }
        String source = from.name();
        String target = to.name();
        if ((source.equals(VARCHAR) || source.equals(TEXT)) && (target.equals(VARCHAR) || target.equals(TEXT))) {
            return length(to) < length(from);
        }
        if (source.equals(VARBIT) && target.equals(VARBIT)) {
            return length(to) < length(from);
        }
        if (source.equals(NUMERIC) && target.equals(NUMERIC)) {
            if (to.modifiers().isEmpty()) {
                return false;
            }
            return from.modifiers().isEmpty() || scale(from) != scale(to) || precision(to) < precision(from);
        }
        if (source.equals(target) && TIMES.contains(source)) {
            return secondsDigits(to) < secondsDigits(from);
        }
        return !(source.equals("cidr") && target.equals("inet"));
    }

    public Kind kind() {
        if (!rewrites()) {
            return Kind.WIDENING;
        }
        if (from.arrayDimensions() != to.arrayDimensions()) {
            return Kind.CONVERSION;
        }
        if (from.arrayDimensions() > 0) {
            return new TypeChange(element(from), element(to)).kind();
        }
        if (from.schema() != null || to.schema() != null) {
            return Kind.CONVERSION;
        }
        String source = from.name();
        String target = to.name();
        if (STRINGS.contains(source) && STRINGS.contains(target)) {
            return widerIf(length(to) >= length(from));
        }
        if (source.equals(VARBIT) && target.equals(VARBIT)) {
            return widerIf(length(to) >= length(from));
        }
        if (INTEGERS.containsKey(source) && INTEGERS.containsKey(target)) {
            return widerIf(INTEGERS.get(target) >= INTEGERS.get(source));
        }
        if (source.equals(NUMERIC) && target.equals(NUMERIC)) {
            boolean keepsDigits =
                    !from.modifiers().isEmpty()
                            && scale(to) >= scale(from)
                            && precision(to) - scale(to) >= precision(from) - scale(from);
            return widerIf(keepsDigits);
        }
        if (isNumber(source) && isNumber(target)) {
            boolean exact = target.equals(NUMERIC) && to.modifiers().isEmpty();
            boolean intoDouble = target.equals("double precision") && !source.equals("bigint") && !source.equals(NUMERIC);
            return widerIf(exact || intoDouble);
        }
        if (source.equals(target) && TIMES.contains(source)) {
            return Kind.NARROWING;
        }
        if (source.equals("date") && TIMESTAMPS.contains(target)) {
            return Kind.WIDENING;
        }
        if (TIMESTAMPS.contains(source) && target.equals("date")) {
            return Kind.NARROWING;
        }
        if (source.equals("inet") && target.equals("cidr")) {
            return Kind.NARROWING;
        }
        if (source.equals("json") && target.equals("jsonb")) {
            return Kind.WIDENING;
        }
        return Kind.CONVERSION;
    }

    private static Kind widerIf(boolean everyValueFits) {
        return everyValueFits ? Kind.WIDENING : Kind.NARROWING;
    }

    private static boolean isNumber(String name) {
        return INTEGERS.containsKey(name) || FLOATS.contains(name) || name.equals(NUMERIC);
    }

    /** A built-in scalar type: not an array and not qualified with a schema. */
    private static boolean plain(DataType type) {
        return type.arrayDimensions() == 0 && type.schema() == null;
    }

    private static DataType element(DataType array) {
        return new DataType(array.schema(), array.name(), array.modifiers(), 0);
    }

    /** The most characters or bits the type holds. {@code character} alone means one character. */
    private static int length(DataType type) {
        OptionalInt declared = modifier(type, 0);
        if (declared.isPresent()) {
            return declared.getAsInt();
        }
        return type.name().equals(CHAR) ? 1 : UNLIMITED;
    }

    private static int precision(DataType numeric) {
        return modifier(numeric, 0).orElse(UNLIMITED);
    }

    private static int scale(DataType numeric) {
        return modifier(numeric, 1).orElse(0);
    }

    /** Fractional digits of seconds; without a modifier PostgreSQL keeps all six. */
    private static int secondsDigits(DataType time) {
        return modifier(time, 0).orElse(6);
    }

    private static OptionalInt modifier(DataType type, int index) {
        List<String> modifiers = type.modifiers();
        if (index >= modifiers.size()) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(modifiers.get(index).strip()));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
