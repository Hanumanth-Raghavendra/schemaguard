package io.github.hanumanthraghavendra.schemaguard.engine.lexer;

import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic;
import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Severity;
import io.github.hanumanthraghavendra.schemaguard.engine.source.SourceSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns PostgreSQL source text into tokens, following the lexical rules in the PostgreSQL
 * manual (section 4.1, "Lexical Structure").
 *
 * <p>Design points:
 *
 * <ul>
 *   <li><b>Never throws on bad input.</b> Problems become {@link Diagnostic}s and lexing
 *       continues, so one unterminated string does not hide the rest of a dump.
 *   <li><b>Keywords are not classified here.</b> Most PostgreSQL keywords are non-reserved
 *       and are only keywords in context, so the lexer emits {@link TokenType#WORD} and
 *       the parser decides.
 *   <li><b>pg_dump output lexes cleanly.</b> psql meta-commands ({@code \connect},
 *       {@code \restrict}) and {@code COPY ... FROM stdin} data blocks are recognised and
 *       emitted as single tokens for the parser to skip.
 * </ul>
 *
 * <p>Known limitation: Unicode-escape syntax ({@code U&'...'}, {@code U&"..."}) is reported
 * as unsupported and its escapes are left undecoded.
 */
public final class Lexer {

    private static final String OPERATOR_CHARS = "+-*/<>=~!@#%^&|`?";

    /** An operator may end in + or - only if it also contains one of these. */
    private static final String OPERATOR_SPECIAL_CHARS = "~!@#%^&|`?";

    private static final char END = '\0';

    private final String src;
    private final int length;
    private final List<Token> tokens = new ArrayList<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    private int pos;
    private int line = 1;
    private int column = 1;

    // State for spotting "COPY ... FROM stdin;", whose data block is not SQL.
    private boolean atStatementStart = true;
    private boolean inCopyStatement;
    private boolean previousWordWasFrom;
    private boolean copyFromStdin;

    private Lexer(String src) {
        this.src = src;
        this.length = src.length();
    }

    /** Lexes {@code sql} completely. The returned token list always ends with {@link TokenType#EOF}. */
    public static LexResult tokenize(String sql) {
        Objects.requireNonNull(sql, "sql");
        return new Lexer(sql).run();
    }

    private LexResult run() {
        while (true) {
            skipTrivia();
            if (pos >= length) {
                break;
            }
            lexToken();
        }
        tokens.add(new Token(TokenType.EOF, "", "", new SourceSpan(pos, pos, line, column)));
        return new LexResult(tokens, diagnostics);
    }

    // ---------------------------------------------------------------- trivia

    private void skipTrivia() {
        while (pos < length) {
            char c = current();
            if (Character.isWhitespace(c)) {
                advance();
            } else if (c == '-' && peek(1) == '-') {
                while (pos < length && current() != '\n') {
                    advance();
                }
            } else if (c == '/' && peek(1) == '*') {
                skipBlockComment();
            } else {
                return;
            }
        }
    }

    /** Block comments nest in PostgreSQL, unlike in C or Java. */
    private void skipBlockComment() {
        Mark start = mark();
        int depth = 0;
        while (pos < length) {
            if (current() == '/' && peek(1) == '*') {
                depth++;
                advance(2);
            } else if (current() == '*' && peek(1) == '/') {
                depth--;
                advance(2);
                if (depth == 0) {
                    return;
                }
            } else {
                advance();
            }
        }
        error(LexErrors.UNTERMINATED_BLOCK_COMMENT, "Unterminated block comment", start);
    }

    // ---------------------------------------------------------------- dispatch

    private void lexToken() {
        Mark start = mark();
        char c = current();
        char next = peek(1);

        if (c == '\\') {
            if (atStatementStart) {
                lexMetaCommand(start);
            } else {
                advance();
                error(LexErrors.UNEXPECTED_CHARACTER, "Unexpected character '\\'", start);
            }
        } else if (c == '\'') {
            lexString(start, false);
        } else if (c == '"') {
            lexQuotedIdentifier(start);
        } else if (c == '$') {
            lexDollar(start);
        } else if (isDigit(c) || (c == '.' && isDigit(next))) {
            lexNumber(start);
        } else if (isIdentifierStart(c)) {
            lexWordOrPrefixedLiteral(start);
        } else if (OPERATOR_CHARS.indexOf(c) >= 0) {
            lexOperator(start);
        } else {
            lexPunctuation(start, c, next);
        }
    }

    private void lexPunctuation(Mark start, char c, char next) {
        TokenType type =
                switch (c) {
                    case '(' -> TokenType.LPAREN;
                    case ')' -> TokenType.RPAREN;
                    case '[' -> TokenType.LBRACKET;
                    case ']' -> TokenType.RBRACKET;
                    case ',' -> TokenType.COMMA;
                    case ';' -> TokenType.SEMICOLON;
                    case '.' -> TokenType.DOT;
                    case ':' -> next == ':' ? TokenType.DOUBLE_COLON : TokenType.COLON;
                    default -> null;
                };
        if (type == null) {
            int codePoint = src.codePointAt(pos);
            advance(Character.charCount(codePoint));
            error(
                    LexErrors.UNEXPECTED_CHARACTER,
                    "Unexpected character " + describe(codePoint),
                    start);
            return;
        }
        advance(type == TokenType.DOUBLE_COLON ? 2 : 1);
        emit(type, start);
    }

    // ---------------------------------------------------------------- words

    private void lexWordOrPrefixedLiteral(Mark start) {
        char c = current();
        char next = peek(1);

        if (next == '\'') {
            switch (c) {
                case 'e', 'E' -> {
                    advance();
                    lexString(start, true);
                    return;
                }
                case 'n', 'N' -> {
                    advance();
                    lexString(start, false);
                    return;
                }
                case 'b', 'B', 'x', 'X' -> {
                    advance();
                    lexBitString(start);
                    return;
                }
                default -> {
                    // An ordinary word that happens to be followed by a string.
                }
            }
        }

        if ((c == 'u' || c == 'U') && next == '&' && (peek(2) == '\'' || peek(2) == '"')) {
            advance(2);
            error(
                    LexErrors.UNSUPPORTED_UNICODE_ESCAPE,
                    "Unicode-escape syntax (U&) is not supported; escapes are left undecoded",
                    start);
            if (current() == '\'') {
                lexString(start, false);
            } else {
                lexQuotedIdentifier(start);
            }
            return;
        }

        while (pos < length && isIdentifierPart(current())) {
            advance();
        }
        String text = slice(start);
        emit(TokenType.WORD, text, asciiLowerCase(text), start);
    }

    // ---------------------------------------------------------------- strings

    /** Lexes a single-quoted string. On entry the cursor is on the opening quote. */
    private void lexString(Mark start, boolean backslashEscapes) {
        advance();
        StringBuilder value = new StringBuilder();
        while (pos < length) {
            char c = current();
            if (c == '\'') {
                if (peek(1) == '\'') {
                    value.append('\'');
                    advance(2);
                    continue;
                }
                advance();
                emit(TokenType.STRING, slice(start), value.toString(), start);
                return;
            }
            if (backslashEscapes && c == '\\') {
                readBackslashEscape(value);
                continue;
            }
            value.append(c);
            advance();
        }
        error(LexErrors.UNTERMINATED_STRING, "Unterminated string literal", start);
        emit(TokenType.STRING, slice(start), value.toString(), start);
    }

    /** Decodes one backslash escape of an {@code E'...'} string. On entry the cursor is on the backslash. */
    private void readBackslashEscape(StringBuilder value) {
        advance();
        if (pos >= length) {
            return;
        }
        char c = current();
        switch (c) {
            case 'b' -> appendAndAdvance(value, '\b');
            case 'f' -> appendAndAdvance(value, '\f');
            case 'n' -> appendAndAdvance(value, '\n');
            case 'r' -> appendAndAdvance(value, '\r');
            case 't' -> appendAndAdvance(value, '\t');
            case 'x' -> {
                int digits = countDigits(pos + 1, 2, 16);
                if (digits == 0) {
                    appendAndAdvance(value, 'x');
                } else {
                    value.append((char) Integer.parseInt(src, pos + 1, pos + 1 + digits, 16));
                    advance(1 + digits);
                }
            }
            case 'u', 'U' -> {
                int wanted = c == 'u' ? 4 : 8;
                if (countDigits(pos + 1, wanted, 16) == wanted) {
                    int codePoint = Integer.parseUnsignedInt(src, pos + 1, pos + 1 + wanted, 16);
                    value.appendCodePoint(Character.isValidCodePoint(codePoint) ? codePoint : 0xFFFD);
                    advance(1 + wanted);
                } else {
                    appendAndAdvance(value, c);
                }
            }
            default -> {
                int digits = countDigits(pos, 3, 8);
                if (digits == 0) {
                    // Covers \\ and \' and any other character, which stands for itself.
                    appendAndAdvance(value, c);
                } else {
                    value.append((char) (Integer.parseInt(src, pos, pos + digits, 8) & 0xFF));
                    advance(digits);
                }
            }
        }
    }

    private void appendAndAdvance(StringBuilder value, char c) {
        value.append(c);
        advance();
    }

    /** Counts up to {@code max} consecutive digits of the given radix starting at {@code from}. */
    private int countDigits(int from, int max, int radix) {
        int count = 0;
        while (count < max
                && from + count < length
                && Character.digit(src.charAt(from + count), radix) >= 0
                && src.charAt(from + count) < 128) {
            count++;
        }
        return count;
    }

    /** Lexes {@code B'...'} or {@code X'...'}. On entry the cursor is on the opening quote. */
    private void lexBitString(Mark start) {
        advance();
        int contentStart = pos;
        while (pos < length && current() != '\'') {
            advance();
        }
        String content = src.substring(contentStart, pos);
        if (pos >= length) {
            error(LexErrors.UNTERMINATED_STRING, "Unterminated bit-string literal", start);
        } else {
            advance();
        }
        emit(TokenType.BIT_STRING, slice(start), content, start);
    }

    /** Lexes a double-quoted identifier. On entry the cursor is on the opening quote. */
    private void lexQuotedIdentifier(Mark start) {
        advance();
        StringBuilder value = new StringBuilder();
        while (pos < length) {
            char c = current();
            if (c == '"') {
                if (peek(1) == '"') {
                    value.append('"');
                    advance(2);
                    continue;
                }
                advance();
                if (value.isEmpty()) {
                    error(
                            LexErrors.EMPTY_QUOTED_IDENTIFIER,
                            "Zero-length quoted identifier",
                            start);
                }
                emit(TokenType.QUOTED_IDENTIFIER, slice(start), value.toString(), start);
                return;
            }
            value.append(c);
            advance();
        }
        error(LexErrors.UNTERMINATED_QUOTED_IDENTIFIER, "Unterminated quoted identifier", start);
        emit(TokenType.QUOTED_IDENTIFIER, slice(start), value.toString(), start);
    }

    /** Lexes {@code $1}, {@code $$...$$} or {@code $tag$...$tag$}. On entry the cursor is on the dollar sign. */
    private void lexDollar(Mark start) {
        if (isDigit(peek(1))) {
            advance();
            while (pos < length && isDigit(current())) {
                advance();
            }
            String text = slice(start);
            emit(TokenType.PARAMETER, text, text.substring(1), start);
            return;
        }

        int tagEnd = pos + 1;
        while (tagEnd < length && isDollarTagPart(src.charAt(tagEnd))) {
            tagEnd++;
        }
        if (tagEnd >= length || src.charAt(tagEnd) != '$') {
            advance();
            error(LexErrors.UNEXPECTED_CHARACTER, "Unexpected character '$'", start);
            return;
        }

        String delimiter = src.substring(pos, tagEnd + 1);
        int bodyStart = tagEnd + 1;
        int close = src.indexOf(delimiter, bodyStart);
        if (close < 0) {
            advanceTo(length);
            error(
                    LexErrors.UNTERMINATED_DOLLAR_STRING,
                    "Unterminated dollar-quoted string, expected closing " + delimiter,
                    start);
            emit(TokenType.STRING, slice(start), src.substring(bodyStart), start);
            return;
        }
        advanceTo(close + delimiter.length());
        emit(TokenType.STRING, slice(start), src.substring(bodyStart, close), start);
    }

    // ---------------------------------------------------------------- numbers

    private void lexNumber(Mark start) {
        if (current() == '0' && "xXoObB".indexOf(peek(1)) >= 0 && isAsciiLetterOrDigit(peek(2))) {
            advance(2);
            while (pos < length && (isAsciiLetterOrDigit(current()) || current() == '_')) {
                advance();
            }
        } else {
            skipDigits();
            // "1..10" is a range, not the number "1." followed by ".10".
            if (current() == '.' && peek(1) != '.') {
                advance();
                skipDigits();
            }
            if (current() == 'e' || current() == 'E') {
                int exponentStart = (peek(1) == '+' || peek(1) == '-') ? 2 : 1;
                if (isDigit(peek(exponentStart))) {
                    advance(exponentStart);
                    skipDigits();
                }
            }
        }
        emit(TokenType.NUMBER, start);
    }

    private void skipDigits() {
        while (pos < length && (isDigit(current()) || (current() == '_' && isDigit(peek(1))))) {
            advance();
        }
    }

    // ---------------------------------------------------------------- operators

    /**
     * Lexes the longest run of operator characters, then applies PostgreSQL's rule that a
     * multi-character operator cannot end in + or - unless it contains one of
     * {@code ~ ! @ # % ^ & | ` ?}. That rule is why {@code a=-1} is {@code a = -1} and not
     * a call to an operator named {@code =-}.
     */
    private void lexOperator(Mark start) {
        while (pos < length && OPERATOR_CHARS.indexOf(current()) >= 0) {
            boolean commentStart =
                    (current() == '-' && peek(1) == '-') || (current() == '/' && peek(1) == '*');
            if (commentStart && pos > start.offset()) {
                break;
            }
            advance();
        }

        String run = slice(start);
        if (run.length() > 1 && !containsAny(run, OPERATOR_SPECIAL_CHARS)) {
            int keep = run.length();
            while (keep > 1 && (run.charAt(keep - 1) == '+' || run.charAt(keep - 1) == '-')) {
                keep--;
            }
            rewind(run.length() - keep);
        }
        emit(TokenType.OPERATOR, start);
    }

    // ---------------------------------------------------------------- pg_dump specifics

    /** A psql meta-command runs to the end of its line. */
    private void lexMetaCommand(Mark start) {
        while (pos < length && current() != '\n') {
            advance();
        }
        String text = slice(start);
        tokens.add(new Token(TokenType.META_COMMAND, text, text.stripTrailing(), span(start)));
    }

    /**
     * Skips the data rows after {@code COPY ... FROM stdin;}. They run from the next line to
     * a line holding only {@code \.} and are data, not SQL.
     */
    private void skipCopyData() {
        while (pos < length && current() != '\n') {
            advance();
        }
        if (pos < length) {
            advance();
        }
        Mark start = mark();
        boolean terminated = false;
        while (pos < length && !terminated) {
            int lineEnd = src.indexOf('\n', pos);
            if (lineEnd < 0) {
                lineEnd = length;
            }
            String row = src.substring(pos, lineEnd);
            terminated = row.equals("\\.") || row.equals("\\.\r");
            advanceTo(Math.min(lineEnd + 1, length));
        }
        if (!terminated) {
            error(
                    LexErrors.UNTERMINATED_COPY_DATA,
                    "COPY data is not terminated by a line containing only \\.",
                    start);
        }
        tokens.add(new Token(TokenType.COPY_DATA, "", "", span(start)));
    }

    // ---------------------------------------------------------------- emitting

    private void emit(TokenType type, Mark start) {
        String text = slice(start);
        emit(type, text, text, start);
    }

    private void emit(TokenType type, String text, String value, Mark start) {
        Token token = new Token(type, text, value, span(start));
        tokens.add(token);
        trackStatement(token);
    }

    private void trackStatement(Token token) {
        if (token.type() == TokenType.SEMICOLON) {
            boolean dataFollows = inCopyStatement && copyFromStdin;
            atStatementStart = true;
            inCopyStatement = false;
            previousWordWasFrom = false;
            copyFromStdin = false;
            if (dataFollows) {
                skipCopyData();
            }
            return;
        }
        if (atStatementStart) {
            atStatementStart = false;
            inCopyStatement = token.isWord("copy");
        } else if (inCopyStatement) {
            if (previousWordWasFrom && token.isWord("stdin")) {
                copyFromStdin = true;
            }
            previousWordWasFrom = token.isWord("from");
        }
    }

    private void error(String code, String message, Mark start) {
        diagnostics.add(new Diagnostic(Severity.ERROR, code, message, span(start)));
    }

    // ---------------------------------------------------------------- cursor

    private record Mark(int offset, int line, int column) {}

    private Mark mark() {
        return new Mark(pos, line, column);
    }

    private SourceSpan span(Mark start) {
        return new SourceSpan(start.offset(), pos, start.line(), start.column());
    }

    private String slice(Mark start) {
        return src.substring(start.offset(), pos);
    }

    private char current() {
        return pos < length ? src.charAt(pos) : END;
    }

    private char peek(int ahead) {
        int index = pos + ahead;
        return index < length ? src.charAt(index) : END;
    }

    private void advance() {
        if (src.charAt(pos) == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        pos++;
    }

    private void advance(int count) {
        for (int i = 0; i < count; i++) {
            advance();
        }
    }

    private void advanceTo(int target) {
        while (pos < target) {
            advance();
        }
    }

    /** Steps back over characters that are known not to include a newline. */
    private void rewind(int count) {
        pos -= count;
        column -= count;
    }

    // ---------------------------------------------------------------- character classes

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isIdentifierStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    private static boolean isIdentifierPart(char c) {
        return c == '_' || c == '$' || Character.isLetterOrDigit(c);
    }

    private static boolean isDollarTagPart(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    private static boolean containsAny(String text, String chars) {
        for (int i = 0; i < text.length(); i++) {
            if (chars.indexOf(text.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Folds only ASCII letters, as PostgreSQL does for unquoted identifiers in a UTF-8
     * database. {@link String#toLowerCase()} would also fold non-ASCII letters and so
     * produce names that differ from what the server stores.
     */
    private static String asciiLowerCase(String text) {
        char[] chars = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                if (chars == null) {
                    chars = text.toCharArray();
                }
                chars[i] = (char) (c + ('a' - 'A'));
            }
        }
        return chars == null ? text : new String(chars);
    }

    private static String describe(int codePoint) {
        if (codePoint > 0x20 && codePoint < 0x7F) {
            return "'" + (char) codePoint + "'";
        }
        return String.format("U+%04X", codePoint);
    }
}
