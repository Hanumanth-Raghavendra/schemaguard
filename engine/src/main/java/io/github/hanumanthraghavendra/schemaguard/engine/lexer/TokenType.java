package io.github.hanumanthraghavendra.schemaguard.engine.lexer;

/** The lexical categories the {@link Lexer} produces. */
public enum TokenType {
    /**
     * An unquoted identifier or keyword. PostgreSQL decides which one it is from context,
     * so the lexer does not: the parser does.
     */
    WORD,
    /** A double-quoted identifier such as {@code "Order"}. Case is preserved. */
    QUOTED_IDENTIFIER,
    /** A string constant: {@code '...'}, {@code E'...'}, {@code N'...'} or dollar-quoted. */
    STRING,
    /** A bit-string constant: {@code B'0101'} or {@code X'1F'}. */
    BIT_STRING,
    NUMBER,
    /** A positional parameter such as {@code $1}. */
    PARAMETER,
    /** Any operator built from PostgreSQL's operator characters, for example {@code =} or {@code ->>}. */
    OPERATOR,
    LPAREN,
    RPAREN,
    LBRACKET,
    RBRACKET,
    COMMA,
    SEMICOLON,
    DOT,
    COLON,
    /** The typecast operator {@code ::}. */
    DOUBLE_COLON,
    /** A psql meta-command line such as {@code \connect} or {@code \restrict}, as found in pg_dump output. */
    META_COMMAND,
    /** The inline data block that follows {@code COPY ... FROM stdin;}, up to and including {@code \.}. */
    COPY_DATA,
    EOF
}
