package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import io.github.hanumanthraghavendra.schemaguard.engine.model.Column;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Constraint;
import io.github.hanumanthraghavendra.schemaguard.engine.model.DataType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Deferrability;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Expression;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Identity;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One action of an {@code ALTER TABLE} statement.
 *
 * <p>Actions are kept as written instead of being applied straight to a table, because the
 * action itself is what carries the risk: {@code SET NOT NULL} and {@code ADD COLUMN} may end
 * in the same schema but take different locks to get there.
 */
public sealed interface AlterAction {

    /**
     * @param constraints constraints written inline on the new column, each as a constraint
     *                    over that one column
     */
    record AddColumn(Column column, List<Constraint> constraints, boolean ifNotExists)
            implements AlterAction {
        public AddColumn {
            Objects.requireNonNull(column, "column");
            constraints = List.copyOf(constraints);
        }
    }

    record DropColumn(String column, boolean ifExists, boolean cascade) implements AlterAction {}

    /** @param using the expression that converts existing values, when given */
    record AlterColumnType(
            String column, DataType type, Optional<String> collation, Optional<Expression> using)
            implements AlterAction {}

    record SetDefault(String column, Expression value) implements AlterAction {}

    record DropDefault(String column) implements AlterAction {}

    record SetNotNull(String column) implements AlterAction {}

    record DropNotNull(String column) implements AlterAction {}

    record AddIdentity(String column, Identity identity) implements AlterAction {}

    record DropIdentity(String column, boolean ifExists) implements AlterAction {}

    record AddConstraint(Constraint constraint) implements AlterAction {
        public AddConstraint {
            Objects.requireNonNull(constraint, "constraint");
        }
    }

    /**
     * {@code ADD [CONSTRAINT name] UNIQUE | PRIMARY KEY USING INDEX index}: promotes an index
     * that already exists, which is how a key is added without a long lock.
     */
    record AddConstraintUsingIndex(
            Optional<String> name, boolean primaryKey, String index, Deferrability deferrability)
            implements AlterAction {}

    record DropConstraint(String name, boolean ifExists, boolean cascade) implements AlterAction {}

    record ValidateConstraint(String name) implements AlterAction {}

    record RenameColumn(String from, String to) implements AlterAction {}

    record RenameConstraint(String from, String to) implements AlterAction {}

    record RenameTable(String to) implements AlterAction {}

    record SetSchema(String schema) implements AlterAction {}
}
