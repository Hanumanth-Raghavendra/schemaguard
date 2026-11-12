package io.github.hanumanthraghavendra.schemaguard.engine.model;

import java.util.Map;

/** PostgreSQL's built-in type aliases and the canonical name each stands for. */
public final class TypeNames {

    private static final Map<String, String> ALIASES =
            Map.ofEntries(
                    Map.entry("int", "integer"),
                    Map.entry("int4", "integer"),
                    Map.entry("int2", "smallint"),
                    Map.entry("int8", "bigint"),
                    Map.entry("bool", "boolean"),
                    Map.entry("float4", "real"),
                    Map.entry("float8", "double precision"),
                    Map.entry("decimal", "numeric"),
                    Map.entry("dec", "numeric"),
                    Map.entry("serial4", "serial"),
                    Map.entry("serial8", "bigserial"),
                    Map.entry("serial2", "smallserial"),
                    Map.entry("varchar", "character varying"),
                    Map.entry("char", "character"),
                    Map.entry("varbit", "bit varying"),
                    Map.entry("timestamptz", "timestamp with time zone"),
                    Map.entry("timetz", "time with time zone"),
                    Map.entry("timestamp", "timestamp without time zone"),
                    Map.entry("time", "time without time zone"));

    private TypeNames() {}

    /** The canonical name for an unquoted built-in type name; other names are returned unchanged. */
    public static String canonical(String unquotedName) {
        return ALIASES.getOrDefault(unquotedName, unquotedName);
    }

    /** True for canonical built-in names that span several words and are never quoted. */
    public static boolean isMultiWordBuiltIn(String name) {
        return name.equals("character varying")
                || name.equals("bit varying")
                || name.equals("double precision")
                || name.endsWith(" time zone")
                || name.startsWith("interval ");
    }

    public static boolean isAlias(String name) {
        return ALIASES.containsKey(name);
    }
}
