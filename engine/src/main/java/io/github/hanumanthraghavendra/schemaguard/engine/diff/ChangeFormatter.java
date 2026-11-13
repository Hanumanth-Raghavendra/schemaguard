package io.github.hanumanthraghavendra.schemaguard.engine.diff;

import io.github.hanumanthraghavendra.schemaguard.engine.model.Expression;
import io.github.hanumanthraghavendra.schemaguard.engine.model.GeneratedColumn;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Identifiers;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Identity;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Index;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Sequence;
import io.github.hanumanthraghavendra.schemaguard.engine.sql.SqlWriter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * One line of plain text per change, for the command line, logs and golden-file tests.
 *
 * <p>Each line starts with a marker: {@code +} added, {@code -} removed, {@code ~} changed,
 * {@code ?} a possible rename. Definitions are printed as SQL.
 */
public final class ChangeFormatter {

    private static final String COSMETIC = " (cosmetic: differs only in parentheses or casts)";

    private ChangeFormatter() {}

    public static String format(SchemaDiff diff) {
        StringBuilder out = new StringBuilder();
        diff.changes().forEach(change -> out.append(format(change)).append('\n'));
        return out.toString();
    }

    public static String format(Change change) {
        return switch (change) {
            case Change.EnumAdded c -> "+ enum " + c.type().name() + " (" + literals(c.type().labels()) + ")";
            case Change.EnumDropped c -> "- enum " + c.type().name();
            case Change.EnumValueAdded c ->
                    "~ enum " + c.type() + ": value " + SqlWriter.literal(c.value()) + " added "
                            + c.before().map(next -> "before " + SqlWriter.literal(next)).orElse("at the end");
            case Change.EnumValueRemoved c -> "~ enum " + c.type() + ": value " + SqlWriter.literal(c.value()) + " removed";
            case Change.EnumValuesReordered c ->
                    "~ enum " + c.type() + ": values reordered (" + literals(c.before()) + ") -> (" + literals(c.after()) + ")";
            case Change.EnumValueRenameCandidate c ->
                    "? enum " + c.type() + ": value " + SqlWriter.literal(c.from()) + " may have been renamed to "
                            + SqlWriter.literal(c.to()) + confidence(c.confidence(), c.evidence());

            case Change.TableAdded c ->
                    "+ table " + c.table().name() + " (" + count(c.table().columns().size(), "column") + ", "
                            + count(c.table().constraints().size(), "constraint") + ")";
            case Change.TableDropped c -> "- table " + c.table().name();
            case Change.TablePersistenceChanged c ->
                    "~ table " + c.table() + ": " + word(c.before()) + " -> " + word(c.after());
            case Change.TablePartitioningChanged c ->
                    "~ table " + c.table() + ": partitioning " + c.before().orElse("none") + " -> " + c.after().orElse("none");
            case Change.TableInheritanceChanged c ->
                    "~ table " + c.table() + ": inherits " + tables(c.before()) + " -> " + tables(c.after());
            case Change.TableRenameCandidate c ->
                    "? table " + c.from() + " may have been renamed to " + c.to() + confidence(c.confidence(), c.evidence());

            case Change.ColumnAdded c ->
                    "+ column " + column(c.table(), c.column().name()) + " " + SqlWriter.columnAttributes(c.column());
            case Change.ColumnDropped c ->
                    "- column " + column(c.table(), c.column().name()) + " " + SqlWriter.columnAttributes(c.column());
            case Change.ColumnTypeChanged c ->
                    "~ column " + column(c.table(), c.column()) + ": type " + c.before().toSql() + " -> " + c.after().toSql();
            case Change.ColumnCollationChanged c ->
                    "~ column " + column(c.table(), c.column()) + ": collation " + c.before().orElse("default") + " -> "
                            + c.after().orElse("default");
            case Change.ColumnNullabilityChanged c ->
                    "~ column " + column(c.table(), c.column()) + ": NOT NULL " + (c.notNull() ? "added" : "dropped");
            case Change.ColumnDefaultChanged c ->
                    "~ column " + column(c.table(), c.column()) + ": default " + expression(c.before()) + " -> "
                            + expression(c.after()) + cosmetic(c.cosmetic());
            case Change.ColumnIdentityChanged c ->
                    "~ column " + column(c.table(), c.column()) + ": identity " + identity(c.before()) + " -> "
                            + identity(c.after());
            case Change.ColumnGeneratedChanged c ->
                    "~ column " + column(c.table(), c.column()) + ": generated " + generated(c.before()) + " -> "
                            + generated(c.after()) + cosmetic(c.cosmetic());
            case Change.ColumnOrderChanged c ->
                    "~ table " + c.table() + ": column order (" + SqlWriter.names(c.before()) + ") -> ("
                            + SqlWriter.names(c.after()) + ")";
            case Change.ColumnRenameCandidate c ->
                    "? column " + column(c.table(), c.from()) + " may have been renamed to " + SqlWriter.name(c.to())
                            + confidence(c.confidence(), c.evidence());

            case Change.ConstraintAdded c ->
                    "+ constraint " + member(c.table(), c.constraint().name().orElse("")) + " "
                            + SqlWriter.constraintBody(c.constraint());
            case Change.ConstraintDropped c ->
                    "- constraint " + member(c.table(), c.constraint().name().orElse("")) + " "
                            + SqlWriter.constraintBody(c.constraint());
            case Change.ConstraintRenamed c ->
                    "~ constraint " + member(c.table(), c.from()) + ": renamed to " + SqlWriter.name(c.to());
            case Change.ConstraintChanged c ->
                    "~ constraint " + member(c.table(), c.after().name().orElse("")) + ": "
                            + SqlWriter.constraintBody(c.before()) + " -> " + SqlWriter.constraintBody(c.after())
                            + cosmetic(c.cosmetic());
            case Change.ConstraintValidated c -> "~ constraint " + member(c.table(), c.constraint()) + ": validated";

            case Change.IndexAdded c -> "+ " + indexKind(c.index()) + " " + indexName(c.index()) + " " + SqlWriter.indexBody(c.index());
            case Change.IndexDropped c -> "- " + indexKind(c.index()) + " " + indexName(c.index()) + " " + SqlWriter.indexBody(c.index());
            case Change.IndexRenamed c ->
                    "~ index " + new QualifiedName(c.table().schema(), c.from()) + ": renamed to " + SqlWriter.name(c.to());
            case Change.IndexChanged c ->
                    "~ index " + indexName(c.after()) + ": " + indexDefinition(c.before()) + " -> "
                            + indexDefinition(c.after()) + cosmetic(c.cosmetic());

            case Change.SequenceAdded c -> "+ sequence " + c.sequence().name() + SqlWriter.sequenceOptions(c.sequence());
            case Change.SequenceDropped c -> "- sequence " + c.sequence().name();
            case Change.SequenceChanged c ->
                    "~ sequence " + c.after().name() + ": "
                            + c.properties().stream()
                                    .map(p -> p + " " + sequenceValue(c.before(), p) + " -> " + sequenceValue(c.after(), p))
                                    .collect(Collectors.joining(", "));
        };
    }

    // ---------------------------------------------------------------- pieces

    private static String column(QualifiedName table, String column) {
        return member(table, column);
    }

    private static String member(QualifiedName parent, String name) {
        return parent.toSql() + "." + Identifiers.quoteIfNeeded(name);
    }

    private static String indexName(Index index) {
        return new QualifiedName(index.table().schema(), index.name().orElse("")).toSql();
    }

    private static String indexKind(Index index) {
        return index.unique() ? "unique index" : "index";
    }

    private static String indexDefinition(Index index) {
        return (index.unique() ? "UNIQUE " : "") + SqlWriter.indexBody(index);
    }

    private static String literals(List<String> values) {
        return values.stream().map(SqlWriter::literal).collect(Collectors.joining(", "));
    }

    private static String tables(List<QualifiedName> names) {
        return names.isEmpty() ? "none" : "(" + names.stream().map(QualifiedName::toSql).collect(Collectors.joining(", ")) + ")";
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static String word(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String identity(Identity identity) {
        return word(identity);
    }

    private static String expression(Optional<Expression> expression) {
        return expression.map(Expression::sql).orElse("none");
    }

    private static String generated(Optional<GeneratedColumn> generated) {
        return generated.map(g -> "(" + g.expression().sql() + ") " + (g.stored() ? "stored" : "virtual")).orElse("none");
    }

    private static String cosmetic(boolean cosmetic) {
        return cosmetic ? COSMETIC : "";
    }

    private static String confidence(double confidence, List<String> evidence) {
        return String.format(Locale.ROOT, " (confidence %.2f: %s)", confidence, String.join("; ", evidence));
    }

    private static String sequenceValue(Sequence sequence, String property) {
        return switch (property) {
            case "persistence" -> word(sequence.persistence());
            case "type" -> sequence.dataType().map(t -> t.toSql()).orElse("default");
            case "start" -> sequence.start().map(String::valueOf).orElse("default");
            case "increment" -> String.valueOf(sequence.increment());
            case "minvalue" -> sequence.minValue().map(String::valueOf).orElse("none");
            case "maxvalue" -> sequence.maxValue().map(String::valueOf).orElse("none");
            case "cache" -> String.valueOf(sequence.cache());
            case "cycle" -> sequence.cycle() ? "yes" : "no";
            case "owned by" -> sequence.ownedBy().map(SqlWriter::ownedBy).orElse("none");
            default -> throw new IllegalArgumentException("Unknown sequence property: " + property);
        };
    }
}
