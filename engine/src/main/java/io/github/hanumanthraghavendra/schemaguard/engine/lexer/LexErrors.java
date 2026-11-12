package io.github.hanumanthraghavendra.schemaguard.engine.lexer;

/** Stable diagnostic codes reported by the {@link Lexer}. */
public final class LexErrors {

    public static final String UNTERMINATED_STRING = "SG-LEX-001";
    public static final String UNTERMINATED_QUOTED_IDENTIFIER = "SG-LEX-002";
    public static final String UNTERMINATED_BLOCK_COMMENT = "SG-LEX-003";
    public static final String UNTERMINATED_DOLLAR_STRING = "SG-LEX-004";
    public static final String UNSUPPORTED_UNICODE_ESCAPE = "SG-LEX-005";
    public static final String UNEXPECTED_CHARACTER = "SG-LEX-006";
    public static final String UNTERMINATED_COPY_DATA = "SG-LEX-007";
    public static final String EMPTY_QUOTED_IDENTIFIER = "SG-LEX-008";

    private LexErrors() {}
}
