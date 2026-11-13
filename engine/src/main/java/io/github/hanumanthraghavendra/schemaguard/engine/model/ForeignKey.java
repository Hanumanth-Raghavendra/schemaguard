package io.github.hanumanthraghavendra.schemaguard.engine.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * @param referencedColumns empty when the reference names no columns, meaning the referenced
 *                          table's primary key
 * @param onDeleteColumns   the column subset of {@code ON DELETE SET NULL (cols)} or
 *                          {@code SET DEFAULT (cols)}; empty means all columns
 * @param notValid          added with {@code NOT VALID}: existing rows are not yet checked
 */
public record ForeignKey(
        Optional<String> name,
        List<String> columns,
        QualifiedName referencedTable,
        List<String> referencedColumns,
        ReferentialAction onDelete,
        ReferentialAction onUpdate,
        List<String> onDeleteColumns,
        MatchType match,
        Deferrability deferrability,
        boolean notValid)
        implements Constraint {

    public ForeignKey {
        Objects.requireNonNull(name, "name");
        columns = List.copyOf(columns);
        Objects.requireNonNull(referencedTable, "referencedTable");
        referencedColumns = List.copyOf(referencedColumns);
        Objects.requireNonNull(onDelete, "onDelete");
        Objects.requireNonNull(onUpdate, "onUpdate");
        onDeleteColumns = List.copyOf(onDeleteColumns);
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(deferrability, "deferrability");
    }

    public ForeignKey withDeferrability(Deferrability value) {
        return new ForeignKey(
                name, columns, referencedTable, referencedColumns, onDelete, onUpdate,
                onDeleteColumns, match, value, notValid);
    }

    public ForeignKey withNotValid(boolean value) {
        return new ForeignKey(
                name, columns, referencedTable, referencedColumns, onDelete, onUpdate,
                onDeleteColumns, match, deferrability, value);
    }

    @Override
    public ForeignKey withName(Optional<String> value) {
        return new ForeignKey(
                value, columns, referencedTable, referencedColumns, onDelete, onUpdate,
                onDeleteColumns, match, deferrability, notValid);
    }

    /** A copy that points the same rules at other columns or another table. */
    public ForeignKey withReferences(
            List<String> localColumns, QualifiedName table, List<String> tableColumns) {
        return new ForeignKey(
                name, localColumns, table, tableColumns, onDelete, onUpdate, onDeleteColumns,
                match, deferrability, notValid);
    }
}
