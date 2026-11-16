package io.github.hanumanthraghavendra.schemaguard.engine.rules;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Check;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Column;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Constraint;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ForeignKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Identity;
import io.github.hanumanthraghavendra.schemaguard.engine.model.PrimaryKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Unique;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import java.util.List;
import java.util.Optional;

/**
 * The one plain statement that applies a change the direct way, which is the migration most
 * people write first. Its {@link Operation} says what that statement locks.
 */
public final class DirectRoute {

    private DirectRoute() {}

    /** Empty if the change needs no statement of its own or cannot be done in one statement. */
    public static Optional<Operation> of(Change change, RuleContext context) {
        return Optional.ofNullable(
                switch (change) {
                    case Change.ColumnAdded c -> addColumn(c.column());
                    case Change.ColumnDropped c -> Operation.DROP_COLUMN;
                    case Change.ColumnTypeChanged c ->
                            new TypeChange(c.before(), c.after()).rewrites()
                                    ? Operation.ALTER_TYPE_REWRITE
                                    : Operation.ALTER_TYPE_NO_REWRITE;
                    case Change.ColumnCollationChanged c ->
                            context.indexed(c.table(), c.column())
                                    ? Operation.ALTER_COLLATION_INDEXED
                                    : Operation.ALTER_COLLATION;
                    case Change.ColumnNullabilityChanged c -> {
                        if (!c.notNull()) {
                            yield Operation.DROP_NOT_NULL;
                        }
                        yield context.provenNotNull(c.table(), c.column())
                                ? Operation.SET_NOT_NULL_WITH_VALID_CHECK
                                : Operation.SET_NOT_NULL;
                    }
                    case Change.ColumnDefaultChanged c ->
                            c.after().isPresent() ? Operation.SET_DEFAULT : Operation.DROP_DEFAULT;
                    case Change.ColumnIdentityChanged c -> {
                        if (c.before() == Identity.NONE) {
                            yield Operation.ADD_IDENTITY;
                        }
                        yield c.after() == Identity.NONE ? Operation.DROP_IDENTITY : Operation.SET_IDENTITY;
                    }
                    case Change.ColumnGeneratedChanged c -> c.after().isEmpty() ? Operation.DROP_EXPRESSION : null;
                    case Change.ConstraintAdded c -> addConstraint(c.constraint());
                    case Change.ConstraintChanged c -> addConstraint(c.after());
                    case Change.ConstraintDropped c ->
                            c.constraint() instanceof ForeignKey ? Operation.DROP_FOREIGN_KEY : Operation.DROP_CONSTRAINT;
                    case Change.ConstraintRenamed c -> Operation.RENAME_CONSTRAINT;
                    case Change.ConstraintValidated c ->
                            context.tableAfter(c.table())
                                    .flatMap(table -> table.constraint(c.constraint()))
                                    .filter(ForeignKey.class::isInstance)
                                    .map(foreignKey -> Operation.VALIDATE_FOREIGN_KEY)
                                    .orElse(Operation.VALIDATE_CHECK);
                    case Change.IndexAdded c -> Operation.CREATE_INDEX;
                    case Change.IndexChanged c -> Operation.CREATE_INDEX;
                    case Change.IndexDropped c -> Operation.DROP_INDEX;
                    case Change.IndexRenamed c -> Operation.RENAME_INDEX;
                    case Change.TablePersistenceChanged c -> Operation.SET_UNLOGGED;
                    case Change.TableDropped c -> Operation.DROP_TABLE;
                    case Change.EnumValueAdded c -> Operation.ADD_ENUM_VALUE;
                    default -> null;
                });
    }

    /**
     * The other tables the direct statement for this change also locks, because a foreign key
     * connects them: the table a new or dropped foreign key points to, or every table a dropped
     * or created table points to. A table that points to itself is not listed again.
     */
    public static List<QualifiedName> referencedTables(Change change) {
        List<Constraint> keys =
                switch (change) {
                    case Change.ConstraintAdded c -> List.of(c.constraint());
                    case Change.ConstraintChanged c -> List.of(c.after());
                    case Change.ConstraintDropped c -> List.of(c.constraint());
                    case Change.TableAdded c -> c.table().constraints();
                    case Change.TableDropped c -> c.table().constraints();
                    default -> List.of();
                };
        Optional<QualifiedName> self = RuleContext.tableOf(change);
        return keys.stream()
                .filter(ForeignKey.class::isInstance)
                .map(key -> ((ForeignKey) key).referencedTable())
                .filter(target -> !self.equals(Optional.of(target)))
                .distinct()
                .sorted()
                .toList();
    }

    /** The statement that adds this column, judged by what the column needs for existing rows. */
    public static Operation addColumn(Column column) {
        if (column.identity() != Identity.NONE) {
            return Operation.ADD_COLUMN_IDENTITY;
        }
        if (column.generated().isPresent()) {
            return Operation.ADD_COLUMN_GENERATED;
        }
        if (column.defaultValue().isEmpty()) {
            return Operation.ADD_COLUMN;
        }
        if (Volatility.of(column.defaultValue().get()) != Volatility.STABLE) {
            return Operation.ADD_COLUMN_VOLATILE_DEFAULT;
        }
        return column.notNull() ? Operation.ADD_COLUMN_NOT_NULL_DEFAULT : Operation.ADD_COLUMN_CONSTANT_DEFAULT;
    }

    public static Operation addConstraint(Constraint constraint) {
        return switch (constraint) {
            case Check check -> check.notValid() ? Operation.ADD_CHECK_NOT_VALID : Operation.ADD_CHECK;
            case ForeignKey key -> key.notValid() ? Operation.ADD_FOREIGN_KEY_NOT_VALID : Operation.ADD_FOREIGN_KEY;
            case PrimaryKey key -> Operation.ADD_PRIMARY_KEY;
            case Unique key -> Operation.ADD_UNIQUE;
        };
    }
}
