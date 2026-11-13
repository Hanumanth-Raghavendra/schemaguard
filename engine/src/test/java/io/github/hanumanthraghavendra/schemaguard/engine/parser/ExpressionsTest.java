package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.engine.model.Expression;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExpressionsTest {

    private static Expression e(String sql) {
        return new Expression(sql);
    }

    @Test
    void fingerprintIgnoresParenthesesAndCasts() {
        assertThat(Expressions.fingerprint(e("total >= (0)::numeric")))
                .isEqualTo(Expressions.fingerprint(e("total >= 0")))
                .isEqualTo("total >= 0");
        assertThat(Expressions.fingerprint(e("(discount is null) or ((discount >= (0)::numeric) and (discount <= (100)::numeric))")))
                .isEqualTo(Expressions.fingerprint(e("discount is null or (discount >= 0 and discount <= 100)")));
        assertThat(Expressions.fingerprint(e("'x'::character varying(10)"))).isEqualTo("'x'");
        assertThat(Expressions.fingerprint(e("a::timestamp(3) with time zone < now()"))).isEqualTo("a < now");
        assertThat(Expressions.fingerprint(e("tags::text[] = '{}'::text[]"))).isEqualTo("tags = '{}'");
        assertThat(Expressions.fingerprint(e("nextval('public.s'::regclass)"))).isEqualTo("nextval 'public.s'");
    }

    @Test
    void fingerprintKeepsACastItCannotRead() {
        assertThat(Expressions.fingerprint(e("a::"))).isEqualTo("a ::");
        assertThat(Expressions.fingerprint(e("a::(b)"))).isEqualTo("a :: b");
    }

    @Test
    void fingerprintStillSeesRealDifferences() {
        assertThat(Expressions.fingerprint(e("a > 0"))).isNotEqualTo(Expressions.fingerprint(e("a >= 0")));
        assertThat(Expressions.fingerprint(e("a > 0"))).isNotEqualTo(Expressions.fingerprint(e("b > 0")));
    }

    @Test
    void referencedColumnsSkipsFunctionsTypesAndQualifiers() {
        Set<String> candidates = Set.of("a", "b", "c", "d", "lower", "text", "t", "unused");
        assertThat(Expressions.referencedColumns(e("a > 0 and lower(b) = 'x' and c::text = 'y' and t.d > a"), candidates))
                .containsExactly("a", "b", "c", "d");
        assertThat(Expressions.referencedColumns(e("1 = 1"), candidates)).isEmpty();
    }

    @Test
    void aReservedWordIsAColumnOnlyWhenQuoted() {
        assertThat(Expressions.referencedColumns(e("\"select\" > 0"), Set.of("select"))).containsExactly("select");
        assertThat(Expressions.referencedColumns(e("x in (select 1)"), Set.of("select"))).isEmpty();
    }

    @Test
    void renameColumnChangesOnlyThatColumn() {
        assertThat(Expressions.renameColumn(e("a > 0 and a_b < a and a(1) = t.a"), "a", "Big Name"))
                .isEqualTo(e("\"Big Name\" > 0 and a_b < \"Big Name\" and a(1) = t.\"Big Name\""));
        assertThat(Expressions.renameColumn(e("x::a > 0"), "a", "z")).isEqualTo(e("x::a > 0"));
        Expression untouched = e("b > 0");
        assertThat(Expressions.renameColumn(untouched, "a", "z")).isSameAs(untouched);
    }

    @Test
    void asColumnRecognisesASingleName() {
        assertThat(Expressions.asColumn(e("a"))).contains("a");
        assertThat(Expressions.asColumn(e("\"Mixed\""))).contains("Mixed");
        assertThat(Expressions.asColumn(e("a + 1"))).isEmpty();
        assertThat(Expressions.asColumn(e("current_user"))).isEmpty();
        assertThat(Expressions.asColumn(e("'a'"))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            textBlock =
                    """
                    lower(code)                         | lower
                    pg_catalog.abs(a)                   | abs
                    a + b                               | expr
                    -a                                  | expr
                    f(a) + 1                            | expr
                    f(a) + g(b)                         | expr
                    code::character varying             | code
                    (lower(code))::text                 | lower
                    f(a)::text::character varying(10)   | f
                    a + b::integer                      | expr
                    case when a > 0 then 1 else 0 end   | case
                    coalesce(a, b)                      | coalesce
                    a                                   | a
                    current_user                        | expr
                    f(a::text)                          | f
                    arr[1]::text                        | expr
                    """)
    void indexColumnNameFollowsPostgres(String expression, String name) {
        assertThat(Expressions.indexColumnName(e(expression))).isEqualTo(name);
    }

    @Test
    void indexColumnNameOfAConstantIsExpr() {
        assertThat(Expressions.indexColumnName(e("'x'::text"))).isEqualTo("expr");
        assertThat(Expressions.indexColumnName(e("f(a"))).isEqualTo("expr");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '"',
            textBlock =
                    """
                    'x'::text                                      | false
                    ''::character varying                          | false
                    '2026-01-01'::timestamp with time zone         | false
                    now()                                          | false
                    current_timestamp                              | false
                    -1                                             | false
                    a + 1                                          | false
                    public.f(a, 1)                                 | false
                    nextval('public.s'::regclass)                  | false
                    array[1, 2]::integer[]                         | false
                    (a is null)                                    | false
                    b is not null                                  | true
                    now() at time zone 'utc'                       | true
                    case when a > 0 then 1 else 0 end              | true
                    a and b                                        | true
                    """)
    void defaultsNeedParenthesesOnlyAroundKeywords(String expression, boolean needed) {
        assertThat(Expressions.needsParenthesesAsDefault(e(expression))).isEqualTo(needed);
    }
}
