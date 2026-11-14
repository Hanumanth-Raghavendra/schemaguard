package io.github.hanumanthraghavendra.schemaguard.engine.rules;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.SchemaDiff;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Check;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ForeignKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Identifiers;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Index;
import io.github.hanumanthraghavendra.schemaguard.engine.model.PrimaryKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Table;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Unique;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.Expressions;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What a rule may look at: the changes, and both schemas around them.
 *
 * <p>The schemas matter because the same change can be harmless or dangerous. Adding a
 * foreign key to a table that is created in the same migration checks no rows, as there are
 * none; adding it to a table that already exists reads every row under a lock.
 */
public final class RuleContext {

    private final SchemaDiff diff;
    private final Set<QualifiedName> renameTargets = new HashSet<>();

    public RuleContext(SchemaDiff diff) {
        this.diff = diff;
        for (Change change : diff.changes()) {
            if (change instanceof Change.TableRenameCandidate rename) {
                renameTargets.add(rename.to());
            }
        }
    }

    public SchemaDiff diff() {
        return diff;
    }

    public Catalog before() {
        return diff.before();
    }

    public Catalog after() {
        return diff.after();
    }

    /** The changes of one kind, in the order of the diff, without the cosmetic ones. */
    public <C extends Change> Stream<C> changes(Class<C> type) {
        return diff.changes().stream().filter(change -> !change.cosmetic()).filter(type::isInstance).map(type::cast);
    }

    /**
     * True if the table can have rows when the migration runs: it exists in the old schema, or
     * it may be an old table under a new name. A table that the migration creates is empty,
     * and nothing done to an empty table takes long or loses data.
     */
    public boolean mayHoldRows(QualifiedName table) {
        return before().table(table).isPresent() || renameTargets.contains(table);
    }

    /** True if the table is in the new schema as well as in the old one. */
    public boolean kept(QualifiedName table) {
        return before().table(table).isPresent() && after().table(table).isPresent();
    }

    public Optional<Table> tableBefore(QualifiedName table) {
        return before().table(table);
    }

    public Optional<Table> tableAfter(QualifiedName table) {
        return after().table(table);
    }

    /**
     * True if the old schema has an index that covers the column: a plain index, an index on an
     * expression that uses it, or the index behind a primary key or unique constraint.
     */
    public boolean indexed(QualifiedName table, String column) {
        Set<String> name = Set.of(column);
        for (Index index : before().indexesOf(table)) {
            boolean inKey =
                    index.columns().stream()
                            .anyMatch(
                                    key ->
                                            key.column().filter(column::equals).isPresent()
                                                    || key.expression()
                                                            .filter(e -> !Expressions.referencedColumns(e, name).isEmpty())
                                                            .isPresent());
            if (inKey || index.include().contains(column)) {
                return true;
            }
        }
        return tableBefore(table).stream()
                .flatMap(t -> t.constraints().stream())
                .anyMatch(
                        constraint ->
                                switch (constraint) {
                                    case PrimaryKey key -> key.columns().contains(column);
                                    case Unique key -> key.columns().contains(column);
                                    case ForeignKey ignored -> false;
                                    case Check ignored -> false;
                                });
    }

    /**
     * True if the old schema already proves the column holds no NULL: a valid constraint
     * {@code CHECK (column IS NOT NULL)}. PostgreSQL 12 and later then skip the scan that
     * {@code SET NOT NULL} otherwise needs.
     */
    public boolean provenNotNull(QualifiedName table, String column) {
        String proof = Identifiers.quoteIfNeeded(column) + " is not null";
        return tableBefore(table).stream()
                .flatMap(t -> t.constraints().stream())
                .anyMatch(
                        constraint ->
                                constraint instanceof Check check
                                        && !check.notValid()
                                        && Expressions.fingerprint(check.expression()).equalsIgnoreCase(proof));
    }

    /** The table a change belongs to; empty for changes to types and sequences. */
    public static Optional<QualifiedName> tableOf(Change change) {
        return switch (change) {
            case Change.TableAdded c -> Optional.of(c.table().name());
            case Change.TableDropped c -> Optional.of(c.table().name());
            case Change.TablePersistenceChanged c -> Optional.of(c.table());
            case Change.TablePartitioningChanged c -> Optional.of(c.table());
            case Change.TableInheritanceChanged c -> Optional.of(c.table());
            case Change.TableRenameCandidate c -> Optional.of(c.from());
            case Change.ColumnAdded c -> Optional.of(c.table());
            case Change.ColumnDropped c -> Optional.of(c.table());
            case Change.ColumnTypeChanged c -> Optional.of(c.table());
            case Change.ColumnCollationChanged c -> Optional.of(c.table());
            case Change.ColumnNullabilityChanged c -> Optional.of(c.table());
            case Change.ColumnDefaultChanged c -> Optional.of(c.table());
            case Change.ColumnIdentityChanged c -> Optional.of(c.table());
            case Change.ColumnGeneratedChanged c -> Optional.of(c.table());
            case Change.ColumnOrderChanged c -> Optional.of(c.table());
            case Change.ColumnRenameCandidate c -> Optional.of(c.table());
            case Change.ConstraintAdded c -> Optional.of(c.table());
            case Change.ConstraintDropped c -> Optional.of(c.table());
            case Change.ConstraintRenamed c -> Optional.of(c.table());
            case Change.ConstraintChanged c -> Optional.of(c.table());
            case Change.ConstraintValidated c -> Optional.of(c.table());
            case Change.IndexAdded c -> Optional.of(c.index().table());
            case Change.IndexDropped c -> Optional.of(c.index().table());
            case Change.IndexRenamed c -> Optional.of(c.table());
            case Change.IndexChanged c -> Optional.of(c.after().table());
            case Change.EnumAdded c -> Optional.empty();
            case Change.EnumDropped c -> Optional.empty();
            case Change.EnumValueAdded c -> Optional.empty();
            case Change.EnumValueRemoved c -> Optional.empty();
            case Change.EnumValuesReordered c -> Optional.empty();
            case Change.EnumValueRenameCandidate c -> Optional.empty();
            case Change.SequenceAdded c -> Optional.empty();
            case Change.SequenceDropped c -> Optional.empty();
            case Change.SequenceChanged c -> Optional.empty();
        };
    }
}
