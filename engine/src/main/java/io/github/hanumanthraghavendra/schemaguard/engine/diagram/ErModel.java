package io.github.hanumanthraghavendra.schemaguard.engine.diagram;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.SchemaDiff;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Column;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ForeignKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.PrimaryKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Table;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The tables of both schemas merged into one entity-relationship model, with every table, column and
 * foreign key marked as added, removed, changed or unchanged. It is what an ER diagram of the change
 * draws.
 *
 * <p>The marks come from the diff's significant changes, so they agree with the change list: a column
 * whose type was only respelled is unchanged, and a possible rename shows as one column removed and
 * one added until a person confirms it.
 *
 * @param tables        every table of either schema, sorted by name
 * @param relationships every foreign key of either schema
 */
public record ErModel(List<Entity> tables, List<Relationship> relationships) {

    /** What the change does to a table, column or foreign key. */
    public enum Status {
        ADDED,
        REMOVED,
        CHANGED,
        UNCHANGED
    }

    /**
     * A table.
     *
     * @param name    schema and name
     * @param status  added, removed, changed (anything about it changed) or unchanged
     * @param columns the new schema's columns in their order, then the removed ones
     */
    public record Entity(QualifiedName name, Status status, List<Attribute> columns) {
        public Entity {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(status, "status");
            columns = List.copyOf(columns);
        }
    }

    /**
     * A column.
     *
     * @param name         column name
     * @param type         type as SQL, from the new schema unless the column was removed
     * @param notNull      whether NULL is refused
     * @param primaryKey   whether it is part of the primary key
     * @param status       added, removed, changed (type, nullability, default, identity, collation or
     *                     generation) or unchanged
     * @param previousType the old type, when the type changed
     */
    public record Attribute(
            String name, String type, boolean notNull, boolean primaryKey, Status status, Optional<String> previousType) {
        public Attribute {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(previousType, "previousType");
        }
    }

    /**
     * A foreign key.
     *
     * @param name              constraint name, when it has one
     * @param from              the table that holds the key
     * @param columns           its columns
     * @param to                the referenced table
     * @param referencedColumns the referenced columns, as the catalog holds them
     * @param status            added, removed, changed or unchanged
     */
    public record Relationship(
            Optional<String> name,
            QualifiedName from,
            List<String> columns,
            QualifiedName to,
            List<String> referencedColumns,
            Status status) {
        public Relationship {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(from, "from");
            columns = List.copyOf(columns);
            Objects.requireNonNull(to, "to");
            referencedColumns = List.copyOf(referencedColumns);
            Objects.requireNonNull(status, "status");
        }
    }

    public ErModel {
        tables = List.copyOf(tables);
        relationships = List.copyOf(relationships);
    }

    /** The model of a diff. */
    public static ErModel of(SchemaDiff diff) {
        Marks marks = Marks.of(diff.significant());
        Set<QualifiedName> names = new TreeSet<>(diff.before().tables().keySet());
        names.addAll(diff.after().tables().keySet());

        List<Entity> entities = new ArrayList<>();
        Map<String, Relationship> relationships = new LinkedHashMap<>();
        for (QualifiedName name : names) {
            Optional<Table> before = diff.before().table(name);
            Optional<Table> after = diff.after().table(name);
            if (after.isEmpty()) {
                Table table = before.orElseThrow();
                entities.add(new Entity(name, Status.REMOVED, attributes(table, Status.REMOVED)));
                foreignKeys(table).forEach(fk -> relationships.put(key(name, fk), relationship(name, fk, Status.REMOVED)));
            } else if (before.isEmpty()) {
                Table table = after.orElseThrow();
                entities.add(new Entity(name, Status.ADDED, attributes(table, Status.ADDED)));
                foreignKeys(table).forEach(fk -> relationships.put(key(name, fk), relationship(name, fk, Status.ADDED)));
            } else {
                entities.add(both(name, before.orElseThrow(), after.orElseThrow(), marks));
                for (ForeignKey fk : foreignKeys(after.orElseThrow())) {
                    String key = key(name, fk);
                    Status status = marks.addedKeys.contains(key) ? Status.ADDED
                            : marks.changedKeys.contains(key) ? Status.CHANGED
                            : Status.UNCHANGED;
                    relationships.put(key, relationship(name, fk, status));
                }
                for (ForeignKey fk : foreignKeys(before.orElseThrow())) {
                    String key = key(name, fk);
                    if (marks.droppedKeys.contains(key)) {
                        relationships.put(key + " (removed)", relationship(name, fk, Status.REMOVED));
                    }
                }
            }
        }
        return new ErModel(entities, List.copyOf(relationships.values()));
    }

    private static Entity both(QualifiedName name, Table before, Table after, Marks marks) {
        Set<String> beforeKey = primaryKeyColumns(before);
        Set<String> afterKey = primaryKeyColumns(after);
        List<Attribute> columns = new ArrayList<>();
        for (Column column : after.columns()) {
            Optional<Column> old = before.column(column.name());
            Status status;
            if (old.isEmpty()) {
                status = Status.ADDED;
            } else if (marks.changedColumns.contains(columnKey(name, column.name()))
                    || beforeKey.contains(column.name()) != afterKey.contains(column.name())) {
                status = Status.CHANGED;
            } else {
                status = Status.UNCHANGED;
            }
            Optional<String> previousType = Optional.ofNullable(marks.previousTypes.get(columnKey(name, column.name())));
            columns.add(attribute(column, afterKey, status, previousType));
        }
        for (Column column : before.columns()) {
            if (after.column(column.name()).isEmpty()) {
                columns.add(attribute(column, beforeKey, Status.REMOVED, Optional.empty()));
            }
        }
        boolean changed = marks.tables.contains(name) || columns.stream().anyMatch(c -> c.status() != Status.UNCHANGED);
        return new Entity(name, changed ? Status.CHANGED : Status.UNCHANGED, columns);
    }

    private static List<Attribute> attributes(Table table, Status status) {
        Set<String> key = primaryKeyColumns(table);
        return table.columns().stream().map(column -> attribute(column, key, status, Optional.empty())).toList();
    }

    private static Attribute attribute(Column column, Set<String> primaryKey, Status status, Optional<String> previousType) {
        return new Attribute(
                column.name(), column.type().toSql(), column.notNull(), primaryKey.contains(column.name()), status, previousType);
    }

    private static Set<String> primaryKeyColumns(Table table) {
        return table.primaryKey().map(PrimaryKey::columns).<Set<String>>map(HashSet::new).orElseGet(Set::of);
    }

    private static List<ForeignKey> foreignKeys(Table table) {
        return table.constraints().stream()
                .filter(ForeignKey.class::isInstance)
                .map(ForeignKey.class::cast)
                .toList();
    }

    private static Relationship relationship(QualifiedName table, ForeignKey fk, Status status) {
        return new Relationship(fk.name(), table, fk.columns(), fk.referencedTable(), fk.referencedColumns(), status);
    }

    /** A foreign key is known by its table and name, or by what it links when it has no name. */
    private static String key(QualifiedName table, ForeignKey fk) {
        return table + "|" + fk.name().orElseGet(() -> fk.columns() + "->" + fk.referencedTable() + fk.referencedColumns());
    }

    private static String columnKey(QualifiedName table, String column) {
        return table + "|" + column;
    }

    /** What the diff's changes say about each table, column and foreign key. */
    private record Marks(
            Set<QualifiedName> tables,
            Set<String> changedColumns,
            Map<String, String> previousTypes,
            Set<String> addedKeys,
            Set<String> droppedKeys,
            Set<String> changedKeys) {

        static Marks of(List<Change> changes) {
            Marks marks = new Marks(new HashSet<>(), new HashSet<>(), new HashMap<>(), new HashSet<>(), new HashSet<>(), new HashSet<>());
            for (Change change : changes) {
                RuleContext.tableOf(change).ifPresent(marks.tables::add);
                switch (change) {
                    case Change.ColumnTypeChanged c -> {
                        marks.changedColumns.add(columnKey(c.table(), c.column()));
                        marks.previousTypes.put(columnKey(c.table(), c.column()), c.before().toSql());
                    }
                    case Change.ColumnNullabilityChanged c -> marks.changedColumns.add(columnKey(c.table(), c.column()));
                    case Change.ColumnDefaultChanged c -> marks.changedColumns.add(columnKey(c.table(), c.column()));
                    case Change.ColumnCollationChanged c -> marks.changedColumns.add(columnKey(c.table(), c.column()));
                    case Change.ColumnIdentityChanged c -> marks.changedColumns.add(columnKey(c.table(), c.column()));
                    case Change.ColumnGeneratedChanged c -> marks.changedColumns.add(columnKey(c.table(), c.column()));
                    case Change.ConstraintAdded c when c.constraint() instanceof ForeignKey fk -> marks.addedKeys.add(key(c.table(), fk));
                    case Change.ConstraintDropped c when c.constraint() instanceof ForeignKey fk -> marks.droppedKeys.add(key(c.table(), fk));
                    case Change.ConstraintChanged c when c.after() instanceof ForeignKey fk -> marks.changedKeys.add(key(c.table(), fk));
                    default -> {
                        // Other changes only mark their table.
                    }
                }
            }
            return marks;
        }
    }
}
