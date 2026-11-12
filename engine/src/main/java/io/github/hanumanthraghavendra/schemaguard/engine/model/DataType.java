package io.github.hanumanthraghavendra.schemaguard.engine.model;

import java.util.List;
import java.util.Objects;

/**
 * A column type in canonical form, so that {@code int}, {@code int4} and {@code integer}
 * compare equal.
 *
 * @param schema          the schema of a qualified user-defined type, or {@code null} for a
 *                        built-in or unqualified type
 * @param name            canonical name, for example {@code integer} or
 *                        {@code timestamp with time zone}
 * @param modifiers       type modifiers as written, for example {@code [12, 2]}
 * @param arrayDimensions 0 for a scalar, 1 for {@code type[]}, and so on
 */
public record DataType(String schema, String name, List<String> modifiers, int arrayDimensions) {

    private static final String WITH_TIME_ZONE = " with time zone";
    private static final String WITHOUT_TIME_ZONE = " without time zone";

    public DataType {
        Objects.requireNonNull(name, "name");
        modifiers = List.copyOf(modifiers);
        if (arrayDimensions < 0) {
            throw new IllegalArgumentException("arrayDimensions cannot be negative");
        }
    }

    /** A scalar built-in or unqualified type with no modifiers. */
    public static DataType of(String name) {
        return new DataType(null, name, List.of(), 0);
    }

    /** The type as it must be written in SQL, for example {@code numeric(12,2)[]}. */
    public String toSql() {
        StringBuilder sql = new StringBuilder();
        if (schema != null) {
            sql.append(Identifiers.quoteIfNeeded(schema)).append('.');
        }
        String printable = printableName();
        String suffix = "";
        // The precision of a time type goes before its zone clause: timestamp(3) with time zone.
        for (String zone : List.of(WITH_TIME_ZONE, WITHOUT_TIME_ZONE)) {
            if (schema == null && printable.endsWith(zone)) {
                suffix = zone;
                printable = printable.substring(0, printable.length() - zone.length());
            }
        }
        sql.append(printable);
        if (!modifiers.isEmpty()) {
            sql.append('(').append(String.join(",", modifiers)).append(')');
        }
        sql.append(suffix);
        sql.append("[]".repeat(arrayDimensions));
        return sql.toString();
    }

    private String printableName() {
        boolean multiWordBuiltIn = schema == null && TypeNames.isMultiWordBuiltIn(name);
        if (multiWordBuiltIn) {
            return name;
        }
        // A quoted name that is also a type alias, such as "char", must stay quoted or it
        // would be read back as the alias (character).
        boolean shadowsAlias = schema == null && TypeNames.isAlias(name);
        return shadowsAlias ? Identifiers.quote(name) : Identifiers.quoteIfNeeded(name);
    }

    @Override
    public String toString() {
        return toSql();
    }
}
