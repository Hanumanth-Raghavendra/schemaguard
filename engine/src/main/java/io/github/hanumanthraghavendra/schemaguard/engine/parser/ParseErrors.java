package io.github.hanumanthraghavendra.schemaguard.engine.parser;

/** Stable diagnostic codes reported by the {@link Parser}. */
public final class ParseErrors {

    /** Warning: a whole statement kind the parser does not handle yet. */
    public static final String UNSUPPORTED_STATEMENT = "SG-PARSE-001";

    /** Error: the statement is not valid SQL as far as the parser can tell. */
    public static final String SYNTAX_ERROR = "SG-PARSE-002";

    /** Warning: a handled statement uses a feature the parser does not model yet. */
    public static final String UNSUPPORTED_FEATURE = "SG-PARSE-003";

    private ParseErrors() {}
}
