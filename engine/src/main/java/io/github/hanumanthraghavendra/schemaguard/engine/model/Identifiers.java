package io.github.hanumanthraghavendra.schemaguard.engine.model;

import java.util.Set;

/** Quoting rules for SQL identifiers. */
public final class Identifiers {

    /** PostgreSQL's fully reserved keywords: none of these can be a table or column name unquoted. */
    private static final Set<String> RESERVED =
            Set.of(
                    "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric",
                    "both", "case", "cast", "check", "collate", "column", "constraint", "create",
                    "current_catalog", "current_date", "current_role", "current_time",
                    "current_timestamp", "current_user", "default", "deferrable", "desc",
                    "distinct", "do", "else", "end", "except", "false", "fetch", "for", "foreign",
                    "from", "grant", "group", "having", "in", "initially", "intersect", "into",
                    "lateral", "leading", "limit", "localtime", "localtimestamp", "not", "null",
                    "offset", "on", "only", "or", "order", "placing", "primary", "references",
                    "returning", "select", "session_user", "some", "symmetric", "system_user",
                    "table", "then", "to", "trailing", "true", "union", "unique", "user", "using",
                    "variadic", "when", "where", "window", "with");

    private Identifiers() {}

    /**
     * True unless {@code name} is a lower-case word of letters, digits and underscores that is
     * not a reserved keyword. This mirrors PostgreSQL's own {@code quote_ident}.
     */
    public static boolean needsQuoting(String name) {
        if (name.isEmpty()) {
            return true;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean letter = (c >= 'a' && c <= 'z') || c == '_';
            boolean digit = c >= '0' && c <= '9';
            if (!(letter || (digit && i > 0))) {
                return true;
            }
        }
        return RESERVED.contains(name);
    }

    /** True for PostgreSQL's fully reserved keywords, given in lower case. */
    public static boolean isReserved(String word) {
        return RESERVED.contains(word);
    }

    public static String quote(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    public static String quoteIfNeeded(String name) {
        return needsQuoting(name) ? quote(name) : name;
    }
}
