package io.github.hanumanthraghavendra.schemaguard.engine.lexer;

import static io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType.BIT_STRING;
import static io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType.COLON;
import static io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType.COMMA;
import static io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType.COPY_DATA;
import static io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType.DOT;
import static io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType.DOUBLE_COLON;
import static io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType.EOF;
import static io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType.LBRACKET;
import static io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType.LPAREN;
import static io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType.META_COMMAND;
import static io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType.NUMBER;
import static io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType.OPERATOR;
import static io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType.PARAMETER;
import static io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType.QUOTED_IDENTIFIER;
import static io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType.RBRACKET;
import static io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType.RPAREN;
import static io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType.SEMICOLON;
import static io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType.STRING;
import static io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType.WORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic;
import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Severity;
import io.github.hanumanthraghavendra.schemaguard.engine.source.SourceSpan;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LexerTest {

    // ---------------------------------------------------------------- helpers

    /** Tokens without the trailing EOF, asserting the input lexed without diagnostics. */
    private static List<Token> lexClean(String sql) {
        LexResult result = Lexer.tokenize(sql);
        assertThat(result.diagnostics()).as("diagnostics for: %s", sql).isEmpty();
        return withoutEof(result);
    }

    private static List<Token> withoutEof(LexResult result) {
        List<Token> tokens = result.tokens();
        assertThat(tokens).last().extracting(Token::type).isEqualTo(EOF);
        return tokens.subList(0, tokens.size() - 1);
    }

    private static List<TokenType> types(String sql) {
        return lexClean(sql).stream().map(Token::type).toList();
    }

    private static List<String> values(String sql) {
        return lexClean(sql).stream().map(Token::value).toList();
    }

    private static Token only(String sql) {
        List<Token> tokens = lexClean(sql);
        assertThat(tokens).hasSize(1);
        return tokens.get(0);
    }

    private static Diagnostic onlyDiagnostic(String sql) {
        List<Diagnostic> diagnostics = Lexer.tokenize(sql).diagnostics();
        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).severity()).isEqualTo(Severity.ERROR);
        return diagnostics.get(0);
    }

    // ---------------------------------------------------------------- tests

    @Nested
    class Basics {

        @Test
        void emptyInputYieldsOnlyEof() {
            LexResult result = Lexer.tokenize("");
            assertThat(result.tokens()).hasSize(1);
            assertThat(result.tokens().get(0).type()).isEqualTo(EOF);
            assertThat(result.tokens().get(0).span()).isEqualTo(new SourceSpan(0, 0, 1, 1));
            assertThat(result.hasErrors()).isFalse();
        }

        @Test
        void nullInputIsRejected() {
            assertThatThrownBy(() -> Lexer.tokenize(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void lexesASimpleCreateTable() {
            String sql = "CREATE TABLE Users (Id INT, name text[]);";
            assertThat(types(sql))
                    .containsExactly(
                            WORD, WORD, WORD, LPAREN, WORD, WORD, COMMA, WORD, WORD, LBRACKET,
                            RBRACKET, RPAREN, SEMICOLON);
            assertThat(values(sql))
                    .containsExactly(
                            "create", "table", "users", "(", "id", "int", ",", "name", "text", "[",
                            "]", ")", ";");
        }

        @Test
        void resultListsAreImmutable() {
            LexResult result = Lexer.tokenize("select 1");
            assertThatThrownBy(() -> result.tokens().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> result.diagnostics().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class Words {

        @Test
        void unquotedWordsAreFoldedToLowerCaseButKeepTheirText() {
            Token token = only("CamelCase_1$x");
            assertThat(token.type()).isEqualTo(WORD);
            assertThat(token.text()).isEqualTo("CamelCase_1$x");
            assertThat(token.value()).isEqualTo("camelcase_1$x");
            assertThat(token.isWord("camelcase_1$x")).isTrue();
            assertThat(token.isWord("other")).isFalse();
            assertThat(token.isOperator("=")).isFalse();
        }

        @Test
        void onlyAsciiLettersAreFolded() {
            // PostgreSQL leaves non-ASCII letters alone in a UTF-8 database.
            assertThat(only("\u00C9COLE").value()).isEqualTo("\u00C9cole");
        }

        @Test
        void quotedIdentifiersKeepCaseAndUnescapeDoubledQuotes() {
            List<Token> tokens = lexClean("\"MyTable\" \"a\"\"b\" \"select\"");
            assertThat(tokens).extracting(Token::type).containsOnly(QUOTED_IDENTIFIER);
            assertThat(tokens).extracting(Token::value).containsExactly("MyTable", "a\"b", "select");
            assertThat(tokens.get(1).text()).isEqualTo("\"a\"\"b\"");
        }

        @Test
        void aWordBeforeAStringIsNotAPrefix() {
            assertThat(types("name 'x'")).containsExactly(WORD, STRING);
            assertThat(types("ab'x'")).containsExactly(WORD, STRING);
        }

        @Test
        void positionalParameter() {
            Token token = only("$12");
            assertThat(token.type()).isEqualTo(PARAMETER);
            assertThat(token.value()).isEqualTo("12");
        }
    }

    @Nested
    class Strings {

        @Test
        void standardStringUnescapesDoubledQuotes() {
            Token token = only("'it''s'");
            assertThat(token.type()).isEqualTo(STRING);
            assertThat(token.text()).isEqualTo("'it''s'");
            assertThat(token.value()).isEqualTo("it's");
        }

        @Test
        void backslashIsLiteralInAStandardString() {
            assertThat(only("'a\\nb'").value()).isEqualTo("a\\nb");
        }

        @Test
        void escapeStringDecodesBackslashEscapes() {
            assertThat(only("E'a\\nb\\tc\\\\d\\'e''f'").value()).isEqualTo("a\nb\tc\\d'e'f");
            assertThat(only("e'\\b\\f\\r'").value()).isEqualTo("\b\f\r");
        }

        @Test
        void escapeStringDecodesNumericEscapes() {
            assertThat(only("E'\\x41\\102\\u0043\\U00000044'").value()).isEqualTo("ABCD");
            assertThat(only("E'\\x4'").value()).isEqualTo("\u0004");
            assertThat(only("E'\\7'").value()).isEqualTo("\u0007");
        }

        @Test
        void malformedEscapesStandForThemselves() {
            assertThat(only("E'\\xZ \\u12 \\q'").value()).isEqualTo("xZ u12 q");
            assertThat(only("E'\\UFFFFFFFF'").value()).isEqualTo("\uFFFD");
        }

        @Test
        void nationalStringIsAPlainString() {
            Token token = only("N'abc'");
            assertThat(token.type()).isEqualTo(STRING);
            assertThat(token.value()).isEqualTo("abc");
        }

        @Test
        void bitStrings() {
            List<Token> tokens = lexClean("B'0101' x'1F'");
            assertThat(tokens).extracting(Token::type).containsExactly(BIT_STRING, BIT_STRING);
            assertThat(tokens).extracting(Token::value).containsExactly("0101", "1F");
        }

        @Test
        void dollarQuotedStringKeepsItsBodyVerbatim() {
            Token token = only("$$a;b 'c' -- not a comment$$");
            assertThat(token.type()).isEqualTo(STRING);
            assertThat(token.value()).isEqualTo("a;b 'c' -- not a comment");
        }

        @Test
        void taggedDollarQuoteIgnoresOtherDelimiters() {
            Token token = only("$fn$ x $$ y $other$ z $fn$");
            assertThat(token.value()).isEqualTo(" x $$ y $other$ z ");
        }

        @Test
        void dollarQuotedBodyDoesNotSplitStatements() {
            String sql = "CREATE FUNCTION f() RETURNS int AS $$ BEGIN RETURN 1; END; $$ LANGUAGE plpgsql;";
            assertThat(types(sql).stream().filter(t -> t == SEMICOLON)).hasSize(1);
        }

        @Test
        void multiLineStringAdvancesTheLineCounter() {
            List<Token> tokens = lexClean("'a\nb' x");
            assertThat(tokens.get(1).span().line()).isEqualTo(2);
            assertThat(tokens.get(1).span().column()).isEqualTo(4);
        }
    }

    @Nested
    class Numbers {

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "0", "42", "3.14", ".5", "5.", "1e10", "1E+10", "1.5e-3", "0x1F", "0o17", "0b101",
                    "1_000_000"
                })
        void lexesAsOneNumber(String literal) {
            Token token = only(literal);
            assertThat(token.type()).isEqualTo(NUMBER);
            assertThat(token.text()).isEqualTo(literal);
        }

        @Test
        void exponentNeedsDigits() {
            assertThat(values("1e")).containsExactly("1", "e");
            assertThat(values("2e+x")).containsExactly("2", "e", "+", "x");
        }

        @Test
        void numberInsideATypeModifier() {
            assertThat(types("numeric(10,2)")).containsExactly(WORD, LPAREN, NUMBER, COMMA, NUMBER, RPAREN);
        }

        @Test
        void aDotBetweenNamesIsNotANumber() {
            assertThat(types("public.t1")).containsExactly(WORD, DOT, WORD);
        }
    }

    @Nested
    class Operators {

        @Test
        void multiCharacterOperators() {
            assertThat(values("a <> b")).containsExactly("a", "<>", "b");
            assertThat(values("a||b")).containsExactly("a", "||", "b");
            assertThat(values("a >= b")).containsExactly("a", ">=", "b");
            assertThat(values("j->>'k'")).containsExactly("j", "->>", "k");
            assertThat(only("=").isOperator("=")).isTrue();
        }

        @Test
        void operatorCannotEndInPlusOrMinusWithoutASpecialCharacter() {
            assertThat(values("a=-1")).containsExactly("a", "=", "-", "1");
            assertThat(values("1+-2")).containsExactly("1", "+", "-", "2");
            assertThat(values("a*-+b")).containsExactly("a", "*", "-", "+", "b");
        }

        @Test
        void operatorMayEndInMinusWhenItContainsASpecialCharacter() {
            assertThat(values("a @- b")).containsExactly("a", "@-", "b");
            assertThat(values("a ?- b")).containsExactly("a", "?-", "b");
        }

        @Test
        void aCommentStartEndsAnOperator() {
            assertThat(values("a +-- comment\n b")).containsExactly("a", "+", "b");
            assertThat(values("a +/* comment */ b")).containsExactly("a", "+", "b");
        }

        @Test
        void typecastAndColon() {
            assertThat(types("x::int")).containsExactly(WORD, DOUBLE_COLON, WORD);
            assertThat(types("a[1:2]"))
                    .containsExactly(WORD, LBRACKET, NUMBER, COLON, NUMBER, RBRACKET);
        }
    }

    @Nested
    class Comments {

        @Test
        void lineAndBlockCommentsAreSkipped() {
            assertThat(values("a -- one\n b /* two */ c")).containsExactly("a", "b", "c");
        }

        @Test
        void blockCommentsNest() {
            assertThat(values("a /* x /* y */ z */ b")).containsExactly("a", "b");
        }

        @Test
        void lineCommentAtEndOfInput() {
            assertThat(values("a -- trailing")).containsExactly("a");
        }
    }

    @Nested
    class Positions {

        @Test
        void tracksOffsetLineAndColumn() {
            List<Token> tokens = lexClean("select\n  1");
            assertThat(tokens.get(0).span()).isEqualTo(new SourceSpan(0, 6, 1, 1));
            assertThat(tokens.get(1).span()).isEqualTo(new SourceSpan(9, 10, 2, 3));
            assertThat(tokens.get(0).span().length()).isEqualTo(6);
        }

        @Test
        void handlesWindowsLineEndings() {
            List<Token> tokens = lexClean("a\r\nb");
            assertThat(tokens.get(1).span()).isEqualTo(new SourceSpan(3, 4, 2, 1));
        }

        @Test
        void columnIsCorrectAfterAnOperatorIsShortened() {
            List<Token> tokens = lexClean("a=-1");
            assertThat(tokens).extracting(t -> t.span().column()).containsExactly(1, 2, 3, 4);
        }

        @Test
        void eofSitsAtTheEndOfTheInput() {
            LexResult result = Lexer.tokenize("a\n");
            Token eof = result.tokens().get(result.tokens().size() - 1);
            assertThat(eof.span()).isEqualTo(new SourceSpan(2, 2, 2, 1));
        }

        @Test
        void spanRejectsInvalidValues() {
            assertThatThrownBy(() -> new SourceSpan(-1, 0, 1, 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SourceSpan(5, 4, 1, 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SourceSpan(0, 0, 0, 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SourceSpan(0, 0, 1, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class PgDump {

        @Test
        void metaCommandsAtStatementStartAreSingleTokens() {
            String sql = "\\restrict abc123\nCREATE TABLE t ();\n\\unrestrict abc123\n";
            List<Token> tokens = lexClean(sql);
            assertThat(tokens)
                    .extracting(Token::type)
                    .containsExactly(
                            META_COMMAND, WORD, WORD, WORD, LPAREN, RPAREN, SEMICOLON, META_COMMAND);
            assertThat(tokens.get(0).value()).isEqualTo("\\restrict abc123");
            assertThat(tokens.get(7).value()).isEqualTo("\\unrestrict abc123");
        }

        @Test
        void metaCommandValueDropsACarriageReturn() {
            assertThat(lexClean("\\connect db\r\nselect 1;").get(0).value()).isEqualTo("\\connect db");
        }

        @Test
        void copyFromStdinDataIsSkipped() {
            String sql =
                    "COPY public.t (a, b) FROM stdin;\n"
                            + "1\tit's -- not SQL\n"
                            + "2\t\\N\n"
                            + "\\.\n"
                            + "SELECT 1;\n";
            List<Token> tokens = lexClean(sql);
            assertThat(tokens)
                    .extracting(Token::type)
                    .containsExactly(
                            WORD, WORD, DOT, WORD, LPAREN, WORD, COMMA, WORD, RPAREN, WORD, WORD,
                            SEMICOLON, COPY_DATA, WORD, NUMBER, SEMICOLON);
            Token data = tokens.get(12);
            assertThat(data.span().line()).isEqualTo(2);
            assertThat(sql.substring(data.span().startOffset(), data.span().endOffset()))
                    .isEqualTo("1\tit's -- not SQL\n2\t\\N\n\\.\n");
            assertThat(tokens.get(13).span().line()).isEqualTo(5);
        }

        @Test
        void copyDataWithWindowsLineEndings() {
            assertThat(types("COPY t FROM stdin;\r\n1\r\n\\.\r\nSELECT 1;"))
                    .containsExactly(WORD, WORD, WORD, WORD, SEMICOLON, COPY_DATA, WORD, NUMBER, SEMICOLON);
        }

        @Test
        void otherCopyFormsHaveNoDataBlock() {
            assertThat(types("COPY t TO stdout;\nSELECT 1;")).doesNotContain(COPY_DATA);
            assertThat(types("COPY t FROM '/tmp/f.csv';\nSELECT 1;")).doesNotContain(COPY_DATA);
            assertThat(types("SELECT a FROM stdin;\nSELECT 1;")).doesNotContain(COPY_DATA);
        }

        @Test
        void realisticDumpLexesWithoutDiagnostics() {
            String sql =
                    """
                    --
                    -- PostgreSQL database dump
                    --
                    \\restrict Xq9
                    SET statement_timeout = 0;
                    SET client_encoding = 'UTF8';
                    SELECT pg_catalog.set_config('search_path', '', false);

                    CREATE TYPE public.order_status AS ENUM (
                        'pending',
                        'shipped'
                    );

                    CREATE TABLE public.orders (
                        id bigint NOT NULL,
                        customer_id integer NOT NULL,
                        status public.order_status DEFAULT 'pending'::public.order_status NOT NULL,
                        total numeric(12,2) DEFAULT 0.00,
                        notes text DEFAULT ''::text,
                        created_at timestamp with time zone DEFAULT now() NOT NULL,
                        CONSTRAINT orders_total_check CHECK ((total >= (0)::numeric))
                    );

                    CREATE SEQUENCE public.orders_id_seq
                        START WITH 1
                        INCREMENT BY 1
                        NO MINVALUE
                        NO MAXVALUE
                        CACHE 1;

                    ALTER TABLE ONLY public.orders ALTER COLUMN id SET DEFAULT nextval('public.orders_id_seq'::regclass);
                    ALTER TABLE ONLY public.orders
                        ADD CONSTRAINT orders_pkey PRIMARY KEY (id);
                    CREATE INDEX orders_customer_idx ON public.orders USING btree (customer_id) WHERE (status <> 'shipped'::public.order_status);
                    \\unrestrict Xq9
                    """;
            List<Token> tokens = lexClean(sql);
            assertThat(tokens.stream().filter(t -> t.type() == SEMICOLON)).hasSize(9);
            assertThat(tokens.stream().filter(t -> t.type() == META_COMMAND)).hasSize(2);
        }
    }

    @Nested
    class Errors {

        @Test
        void unterminatedString() {
            Diagnostic diagnostic = onlyDiagnostic("select 'abc");
            assertThat(diagnostic.code()).isEqualTo(LexErrors.UNTERMINATED_STRING);
            assertThat(diagnostic.span()).isEqualTo(new SourceSpan(7, 11, 1, 8));
            assertThat(withoutEof(Lexer.tokenize("select 'abc")).get(1).value()).isEqualTo("abc");
        }

        @Test
        void unterminatedEscapeStringEndingInABackslash() {
            assertThat(onlyDiagnostic("E'abc\\").code()).isEqualTo(LexErrors.UNTERMINATED_STRING);
        }

        @Test
        void unterminatedBitString() {
            assertThat(onlyDiagnostic("B'0101").code()).isEqualTo(LexErrors.UNTERMINATED_STRING);
        }

        @Test
        void unterminatedQuotedIdentifier() {
            assertThat(onlyDiagnostic("\"abc").code())
                    .isEqualTo(LexErrors.UNTERMINATED_QUOTED_IDENTIFIER);
        }

        @Test
        void emptyQuotedIdentifier() {
            assertThat(onlyDiagnostic("\"\"").code()).isEqualTo(LexErrors.EMPTY_QUOTED_IDENTIFIER);
        }

        @Test
        void unterminatedBlockComment() {
            Diagnostic diagnostic = onlyDiagnostic("a /* b /* c */");
            assertThat(diagnostic.code()).isEqualTo(LexErrors.UNTERMINATED_BLOCK_COMMENT);
            assertThat(diagnostic.span().startOffset()).isEqualTo(2);
        }

        @Test
        void unterminatedDollarQuotedString() {
            Diagnostic diagnostic = onlyDiagnostic("$fn$ body $$");
            assertThat(diagnostic.code()).isEqualTo(LexErrors.UNTERMINATED_DOLLAR_STRING);
            assertThat(diagnostic.message()).contains("$fn$");
        }

        @Test
        void aLoneDollarIsUnexpected() {
            assertThat(onlyDiagnostic("a $ b").code()).isEqualTo(LexErrors.UNEXPECTED_CHARACTER);
            assertThat(onlyDiagnostic("a $tag").code()).isEqualTo(LexErrors.UNEXPECTED_CHARACTER);
        }

        @Test
        void unterminatedCopyData() {
            LexResult result = Lexer.tokenize("COPY t FROM stdin;\n1\n2\n");
            assertThat(result.diagnostics())
                    .extracting(Diagnostic::code)
                    .containsExactly(LexErrors.UNTERMINATED_COPY_DATA);
            assertThat(withoutEof(result)).last().extracting(Token::type).isEqualTo(COPY_DATA);
        }

        @Test
        void unexpectedCharactersAreReportedAndSkipped() {
            LexResult result = Lexer.tokenize("a { b \u20AC c \uD83D\uDE00 d");
            assertThat(result.diagnostics())
                    .extracting(Diagnostic::message)
                    .containsExactly(
                            "Unexpected character '{'",
                            "Unexpected character U+20AC",
                            "Unexpected character U+1F600");
            assertThat(withoutEof(result)).extracting(Token::value).containsExactly("a", "b", "c", "d");
        }

        @Test
        void backslashInsideAStatementIsUnexpected() {
            Diagnostic diagnostic = onlyDiagnostic("select \\ 1");
            assertThat(diagnostic.code()).isEqualTo(LexErrors.UNEXPECTED_CHARACTER);
            assertThat(diagnostic.span()).isEqualTo(new SourceSpan(7, 8, 1, 8));
        }

        @Test
        void unicodeEscapeSyntaxIsReportedButStillTokenized() {
            LexResult string = Lexer.tokenize("U&'d\\0061t'");
            assertThat(string.diagnostics())
                    .extracting(Diagnostic::code)
                    .containsExactly(LexErrors.UNSUPPORTED_UNICODE_ESCAPE);
            assertThat(withoutEof(string)).extracting(Token::type).containsExactly(STRING);

            LexResult identifier = Lexer.tokenize("u&\"d\\0061t\"");
            assertThat(identifier.hasErrors()).isTrue();
            assertThat(withoutEof(identifier)).extracting(Token::type).containsExactly(QUOTED_IDENTIFIER);
        }

        @Test
        void lexingContinuesAfterAnError() {
            LexResult result = Lexer.tokenize("a { b; c");
            assertThat(result.diagnostics()).hasSize(1);
            assertThat(withoutEof(result)).extracting(Token::value).containsExactly("a", "b", ";", "c");
        }
    }
}
