package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.only;
import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.rows;
import static io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserAssertions.syntaxError;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.engine.model.Expression;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Index;
import io.github.hanumanthraghavendra.schemaguard.engine.model.IndexColumn;
import io.github.hanumanthraghavendra.schemaguard.engine.model.NullsOrder;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.model.SortOrder;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class IndexParserTest {

    private static Statement.CreateIndex create(String sql) {
        return only(sql, Statement.CreateIndex.class);
    }

    private static Index index(String sql) {
        return create(sql).index();
    }

    private static IndexColumn key(String keyClause) {
        Index index = index("CREATE INDEX i ON t (" + keyClause + ")");
        assertThat(index.columns()).hasSize(1);
        return index.columns().get(0);
    }

    private static IndexColumn expression(String sql, SortOrder order, NullsOrder nulls) {
        return new IndexColumn(
                Optional.empty(), Optional.of(new Expression(sql)), Optional.empty(), Optional.empty(), order, nulls);
    }

    @Test
    void simpleIndex() {
        Statement.CreateIndex statement = create("CREATE INDEX idx ON orders (customer_id);");
        Index index = statement.index();
        assertThat(index.name()).contains("idx");
        assertThat(index.table()).isEqualTo(new QualifiedName("public", "orders"));
        assertThat(index.unique()).isFalse();
        assertThat(index.method()).isEqualTo("btree");
        assertThat(index.columns()).containsExactly(IndexColumn.ofColumn("customer_id"));
        assertThat(index.include()).isEmpty();
        assertThat(index.nullsNotDistinct()).isFalse();
        assertThat(index.predicate()).isEmpty();
        assertThat(statement.concurrently()).isFalse();
        assertThat(statement.ifNotExists()).isFalse();
        assertThat(statement.only()).isFalse();
    }

    @Test
    void everyClause() {
        Statement.CreateIndex statement =
                create(
                        """
                        CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS "Idx" ON ONLY shop.orders USING BTREE (
                            customer_id DESC NULLS LAST,
                            lower(email) COLLATE "C" text_pattern_ops,
                            (total * 2) ASC NULLS FIRST,
                            tags public.gin_ops (siglen = 32)
                        ) INCLUDE (status, total) NULLS NOT DISTINCT WITH (fillfactor = 70) TABLESPACE fast
                          WHERE (status <> 'shipped'::text);
                        """);
        assertThat(statement.concurrently()).isTrue();
        assertThat(statement.ifNotExists()).isTrue();
        assertThat(statement.only()).isTrue();

        Index index = statement.index();
        assertThat(index.name()).contains("Idx");
        assertThat(index.table()).isEqualTo(new QualifiedName("shop", "orders"));
        assertThat(index.unique()).isTrue();
        assertThat(index.method()).isEqualTo("btree");
        assertThat(index.columns())
                .containsExactly(
                        new IndexColumn(
                                Optional.of("customer_id"),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                SortOrder.DESC,
                                NullsOrder.LAST),
                        new IndexColumn(
                                Optional.empty(),
                                Optional.of(new Expression("lower(email)")),
                                Optional.of("\"C\""),
                                Optional.of("text_pattern_ops"),
                                SortOrder.ASC,
                                NullsOrder.LAST),
                        expression("total * 2", SortOrder.ASC, NullsOrder.FIRST),
                        new IndexColumn(
                                Optional.of("tags"),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of("public.gin_ops(siglen = 32)"),
                                SortOrder.ASC,
                                NullsOrder.LAST));
        assertThat(index.include()).containsExactly("status", "total");
        assertThat(index.nullsNotDistinct()).isTrue();
        assertThat(index.predicate()).contains(new Expression("status <> 'shipped'::text"));
    }

    @Test
    void theNameIsOptional() {
        assertThat(index("CREATE INDEX ON t (a)").name()).isEmpty();
        Statement.CreateIndex concurrent = create("CREATE INDEX CONCURRENTLY ON t (a)");
        assertThat(concurrent.concurrently()).isTrue();
        assertThat(concurrent.index().name()).isEmpty();
    }

    @Test
    void nullsDefaultFollowsTheSortOrder() {
        assertThat(key("a")).isEqualTo(IndexColumn.ofColumn("a"));
        assertThat(key("a ASC").nulls()).isEqualTo(NullsOrder.LAST);
        assertThat(key("a DESC").nulls()).isEqualTo(NullsOrder.FIRST);
        assertThat(key("a DESC NULLS LAST").nulls()).isEqualTo(NullsOrder.LAST);
        assertThat(key("a NULLS FIRST").nulls()).isEqualTo(NullsOrder.FIRST);
    }

    @Test
    void expressionKeys() {
        assertThat(key("public.f(a, b)")).isEqualTo(expression("public.f(a, b)", SortOrder.ASC, NullsOrder.LAST));
        assertThat(key("lower((email)::text)"))
                .isEqualTo(expression("lower((email)::text)", SortOrder.ASC, NullsOrder.LAST));
        assertThat(key("((a + b)) DESC")).isEqualTo(expression("a + b", SortOrder.DESC, NullsOrder.FIRST));
        assertThat(key("\"Mixed\"").column()).contains("Mixed");
    }

    @Test
    void operatorClassAndMethod() {
        Index index = index("CREATE INDEX i ON docs USING GIN (body jsonb_path_ops, title \"Custom Ops\")");
        assertThat(index.method()).isEqualTo("gin");
        assertThat(index.columns().get(0).operatorClass()).contains("jsonb_path_ops");
        assertThat(index.columns().get(1).operatorClass()).contains("\"Custom Ops\"");
    }

    @Test
    void nullsDistinctIsTheDefault() {
        assertThat(index("CREATE UNIQUE INDEX i ON t (a) NULLS DISTINCT").nullsNotDistinct()).isFalse();
    }

    @Test
    void predicateRunsToTheEndOfTheStatement() {
        assertThat(index("CREATE INDEX i ON t (a) WHERE deleted_at IS NULL AND a > 0").predicate())
                .contains(new Expression("deleted_at is null and a > 0"));
    }

    @Test
    void indexesWrittenDifferentlyCompareEqual() {
        assertThat(index("create index i on t (a, b desc)"))
                .isEqualTo(index("CREATE INDEX i ON public.t USING btree (a ASC NULLS LAST, b DESC NULLS FIRST)"));
    }

    static Stream<Arguments> syntaxErrorCases() {
        return rows(
                """
                CREATE UNIQUE INDEX                     | Expected an index name but found end of statement
                CREATE INDEX i t (a)                    | Expected ON but found 't'
                CREATE INDEX i ON t                     | Expected '(' but found end of statement
                CREATE INDEX i ON t USING (a)           | Expected an index method but found '('
                CREATE INDEX i ON t ()                  | Expected a column name or expression but found ')'
                CREATE INDEX i ON t (a                  | Expected ',' or ')' but found end of statement
                CREATE INDEX i ON t (s.a)               | An index column cannot be a qualified name
                CREATE INDEX i ON t (a NULLS MAYBE)     | Expected FIRST or LAST but found 'MAYBE'
                CREATE INDEX i ON t (a) NULLS WRONG     | Expected DISTINCT but found 'WRONG'
                CREATE INDEX i ON t (a) WHERE           | Expected a condition after WHERE but found end of statement
                CREATE INDEX i ON t (a) BOGUS           | Unexpected 'BOGUS' at the end of the statement
                CREATE INDEX i ON t (f(a                | Unbalanced parentheses: this '(' is never closed
                """);
    }

    @ParameterizedTest
    @MethodSource("syntaxErrorCases")
    void reportsAPreciseMessage(String sql, String message) {
        assertThat(syntaxError(sql)).isEqualTo(message);
    }
}
