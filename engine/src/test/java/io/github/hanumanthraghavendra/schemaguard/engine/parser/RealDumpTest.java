package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Check;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Column;
import io.github.hanumanthraghavendra.schemaguard.engine.model.EnumType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Expression;
import io.github.hanumanthraghavendra.schemaguard.engine.model.GeneratedColumn;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Index;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Sequence;
import io.github.hanumanthraghavendra.schemaguard.engine.model.SortOrder;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Table;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Runs the parser over a schema dumped by a real PostgreSQL 16 server. See
 * {@code src/test/resources/golden/README.md} for how the fixture was made.
 */
class RealDumpTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = RealDumpTest.class.getResourceAsStream("/golden/shop/" + name)) {
            assertThat(in).as("test resource %s", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static <T extends Statement> List<T> statements(ParseResult result, Class<T> type) {
        return result.statements().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static Table table(ParseResult result, String name) {
        return result.tables().stream()
                .filter(t -> t.name().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No table " + name));
    }

    @Test
    void pgDumpOutputParsesWithOnlyTheExpectedWarnings() throws IOException {
        ParseResult result = Parser.parse(resource("pg_dump_16.sql"));

        assertThat(result.hasErrors()).isFalse();
        assertThat(result.diagnostics())
                .extracting(Diagnostic::message)
                .containsExactly(
                        "Unsupported statement: CREATE FUNCTION. It was not analysed.",
                        "Unsupported statement: CREATE VIEW. It was not analysed.",
                        "Unsupported feature: ALTER TABLE ... ATTACH PARTITION",
                        "Unsupported statement: CREATE TRIGGER. It was not analysed.");
        assertThat(statements(result, Statement.Invalid.class)).isEmpty();
    }

    @Test
    void everyTableIndexSequenceAndEnumIsFound() throws IOException {
        ParseResult result = Parser.parse(resource("pg_dump_16.sql"));

        assertThat(result.tables())
                .extracting(t -> t.name().toSql())
                .containsExactlyInAnyOrder(
                        "public.\"Mixed Case\"",
                        "shop.audit",
                        "shop.audit_2026",
                        "shop.customers",
                        "shop.events",
                        "shop.orders",
                        "shop.order_items");
        assertThat(statements(result, Statement.CreateIndex.class))
                .extracting(s -> s.index().name().orElseThrow())
                .containsExactlyInAnyOrder(
                        "customers_lower_email_idx",
                        "customers_meta_idx",
                        "customers_name_idx",
                        "orders_customer_idx",
                        "orders_expr_idx",
                        "orders_open_idx");
        assertThat(statements(result, Statement.CreateSequence.class))
                .extracting(s -> s.sequence().name().toSql())
                .containsExactlyInAnyOrder(
                        "shop.countdown", "shop.events_id_seq", "shop.invoice_no", "shop.orders_id_seq");
        assertThat(statements(result, Statement.CreateEnum.class))
                .extracting(Statement.CreateEnum::type)
                .containsExactly(
                        new EnumType(
                                new QualifiedName("shop", "order_status"),
                                List.of("pending", "paid", "shipped", "cancelled")));
        assertThat(statements(result, Statement.AlterTable.class)).hasSize(11);
        assertThat(statements(result, Statement.AlterSequenceOwner.class)).hasSize(2);
    }

    @Test
    void theOrdersTableIsReadCorrectly() throws IOException {
        Table orders = table(Parser.parse(resource("pg_dump_16.sql")), "orders");

        assertThat(orders.columns())
                .extracting(Column::name)
                .containsExactly(
                        "id", "customer_id", "status", "total", "discount", "note", "placed_at", "total_with_tax");
        assertThat(orders.columns())
                .extracting(c -> c.type().toSql())
                .containsExactly(
                        "integer",
                        "bigint",
                        "shop.order_status",
                        "numeric(12,2)",
                        "numeric(5,2)",
                        "text",
                        "timestamp(3) without time zone",
                        "numeric");
        assertThat(orders.columns())
                .extracting(Column::notNull)
                .containsExactly(true, true, true, true, false, false, false, false);
        assertThat(orders.column("status").orElseThrow().defaultValue())
                .contains(new Expression("'pending'::shop.order_status"));
        assertThat(orders.column("note").orElseThrow().collation()).contains("pg_catalog.\"C\"");
        assertThat(orders.column("placed_at").orElseThrow().defaultValue())
                .contains(new Expression("current_timestamp"));
        assertThat(orders.column("total_with_tax").orElseThrow().generated())
                .contains(new GeneratedColumn(new Expression("total * 1.18"), true));
        assertThat(orders.constraints()).hasSize(2).allMatch(Check.class::isInstance);
        assertThat(orders.constraints())
                .extracting(c -> c.name().orElseThrow())
                .containsExactly("discount_range", "orders_total_check");
    }

    @Test
    void indexesAndSequencesAreReadCorrectly() throws IOException {
        ParseResult result = Parser.parse(resource("pg_dump_16.sql"));
        List<Index> indexes =
                statements(result, Statement.CreateIndex.class).stream().map(Statement.CreateIndex::index).toList();

        Index expressionIndex = indexes.stream().filter(i -> i.name().orElseThrow().equals("orders_expr_idx")).findFirst().orElseThrow();
        assertThat(expressionIndex.columns().get(0).expression())
                .contains(new Expression("total - coalesce(discount, (0)::numeric)"));
        assertThat(expressionIndex.columns().get(1).column()).contains("status");

        Index partial = indexes.stream().filter(i -> i.name().orElseThrow().equals("orders_open_idx")).findFirst().orElseThrow();
        assertThat(partial.columns().get(0).order()).isEqualTo(SortOrder.DESC);
        assertThat(partial.predicate()).contains(new Expression("status <> 'shipped'::shop.order_status"));

        Index covering = indexes.stream().filter(i -> i.name().orElseThrow().equals("customers_name_idx")).findFirst().orElseThrow();
        assertThat(covering.columns().get(0).operatorClass()).contains("text_pattern_ops");
        assertThat(covering.include()).containsExactly("email");

        Index gin = indexes.stream().filter(i -> i.name().orElseThrow().equals("customers_meta_idx")).findFirst().orElseThrow();
        assertThat(gin.method()).isEqualTo("gin");

        List<Sequence> sequences =
                statements(result, Statement.CreateSequence.class).stream().map(Statement.CreateSequence::sequence).toList();
        Sequence countdown = sequences.stream().filter(s -> s.name().name().equals("countdown")).findFirst().orElseThrow();
        assertThat(countdown.increment()).isEqualTo(-1);
        assertThat(countdown.minValue()).contains(-100L);
        // The source says MAXVALUE -1. That is the default for a descending sequence, so
        // pg_dump writes NO MAXVALUE. The catalog must treat the two as equal.
        assertThat(countdown.maxValue()).isEmpty();
        assertThat(countdown.start()).contains(-1L);
        Sequence invoiceNo = sequences.stream().filter(s -> s.name().name().equals("invoice_no")).findFirst().orElseThrow();
        assertThat(invoiceNo.start()).contains(1000L);
        assertThat(invoiceNo.increment()).isEqualTo(5);
        assertThat(invoiceNo.maxValue()).contains(999999L);
        assertThat(invoiceNo.cycle()).isTrue();
    }

    @Test
    void theHandWrittenSourceParsesToo() throws IOException {
        ParseResult result = Parser.parse(resource("source.sql"));

        assertThat(result.hasErrors()).isFalse();
        assertThat(result.diagnostics())
                .extracting(Diagnostic::message)
                .containsExactly(
                        "Unsupported feature: PARTITION OF",
                        "Unsupported statement: CREATE VIEW. It was not analysed.",
                        "Unsupported statement: CREATE FUNCTION. It was not analysed.",
                        "Unsupported statement: CREATE TRIGGER. It was not analysed.");
        assertThat(result.tables()).hasSize(6);
    }
}
