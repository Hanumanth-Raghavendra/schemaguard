package io.github.hanumanthraghavendra.schemaguard.engine.diff;

import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Check;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Column;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Constraint;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Expression;
import io.github.hanumanthraghavendra.schemaguard.engine.model.GeneratedColumn;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Index;
import io.github.hanumanthraghavendra.schemaguard.engine.model.IndexColumn;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Table;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.Expressions;
import java.util.Map;
import java.util.TreeMap;

/**
 * Copies of model objects with every expression replaced by its
 * {@link Expressions#fingerprint fingerprint}. Two objects whose normalized forms are equal
 * differ at most in parentheses and casts, which is how PostgreSQL's printing of an expression
 * usually differs from how it was written.
 */
public final class Normalized {

    private Normalized() {}

    public static Expression expression(Expression expression) {
        String fingerprint = Expressions.fingerprint(expression);
        return fingerprint.isBlank() ? expression : new Expression(fingerprint);
    }

    public static Column column(Column column) {
        return column.withDefaultValue(column.defaultValue().map(Normalized::expression))
                .withGenerated(column.generated().map(g -> new GeneratedColumn(expression(g.expression()), g.stored())));
    }

    public static Constraint constraint(Constraint constraint) {
        return constraint instanceof Check check ? check.withExpression(expression(check.expression())) : constraint;
    }

    public static Index index(Index index) {
        return new Index(
                index.name(),
                index.table(),
                index.unique(),
                index.method(),
                index.columns().stream()
                        .map(
                                key ->
                                        new IndexColumn(
                                                key.column(),
                                                key.expression().map(Normalized::expression),
                                                key.collation(),
                                                key.operatorClass(),
                                                key.order(),
                                                key.nulls()))
                        .toList(),
                index.include(),
                index.nullsNotDistinct(),
                index.predicate().map(Normalized::expression));
    }

    public static Table table(Table table) {
        return table.withColumns(table.columns().stream().map(Normalized::column).toList())
                .withConstraints(table.constraints().stream().map(Normalized::constraint).toList());
    }

    public static Catalog catalog(Catalog catalog) {
        Map<QualifiedName, Table> tables = new TreeMap<>();
        catalog.tables().forEach((name, table) -> tables.put(name, table(table)));
        Map<QualifiedName, Index> indexes = new TreeMap<>();
        catalog.indexes().forEach((name, index) -> indexes.put(name, index(index)));
        return new Catalog(tables, catalog.enums(), catalog.sequences(), indexes);
    }
}
