package io.github.hanumanthraghavendra.schemaguard.engine.diff;

import io.github.hanumanthraghavendra.schemaguard.engine.model.Column;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Constraint;
import io.github.hanumanthraghavendra.schemaguard.engine.model.DataType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.EnumType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Expression;
import io.github.hanumanthraghavendra.schemaguard.engine.model.GeneratedColumn;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Identity;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Index;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Persistence;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Sequence;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Table;
import java.util.List;
import java.util.Optional;

/**
 * One difference between two catalogs.
 *
 * <p>Changes say what differs, not how to get from one schema to the other; that is the
 * planner's job. They are literal: a dropped table and the indexes that go with it are each
 * listed. A possible rename is reported as a candidate next to the drop and the add it would
 * explain, and the diff never chooses between the two readings.
 *
 * <p>Sealed, so every {@code switch} over changes is checked for completeness by the compiler.
 */
public sealed interface Change {

    /** True if the difference is probably only in how PostgreSQL printed an expression. */
    default boolean cosmetic() {
        return false;
    }

    // ---------------------------------------------------------------- enumerated types

    record EnumAdded(EnumType type) implements Change {}

    record EnumDropped(EnumType type) implements Change {}

    /** @param before the existing value the new one is placed in front of; empty means at the end */
    record EnumValueAdded(QualifiedName type, String value, Optional<String> before) implements Change {}

    record EnumValueRemoved(QualifiedName type, String value) implements Change {}

    /** The values both versions have are in a different order. Lists are complete, before and after. */
    record EnumValuesReordered(QualifiedName type, List<String> before, List<String> after) implements Change {
        public EnumValuesReordered {
            before = List.copyOf(before);
            after = List.copyOf(after);
        }
    }

    record EnumValueRenameCandidate(
            QualifiedName type, String from, String to, double confidence, List<String> evidence)
            implements Change {
        public EnumValueRenameCandidate {
            evidence = List.copyOf(evidence);
        }
    }

    // ---------------------------------------------------------------- tables

    record TableAdded(Table table) implements Change {}

    record TableDropped(Table table) implements Change {}

    record TablePersistenceChanged(QualifiedName table, Persistence before, Persistence after) implements Change {}

    record TablePartitioningChanged(QualifiedName table, Optional<String> before, Optional<String> after)
            implements Change {}

    record TableInheritanceChanged(QualifiedName table, List<QualifiedName> before, List<QualifiedName> after)
            implements Change {
        public TableInheritanceChanged {
            before = List.copyOf(before);
            after = List.copyOf(after);
        }
    }

    record TableRenameCandidate(QualifiedName from, QualifiedName to, double confidence, List<String> evidence)
            implements Change {
        public TableRenameCandidate {
            evidence = List.copyOf(evidence);
        }
    }

    // ---------------------------------------------------------------- columns

    record ColumnAdded(QualifiedName table, Column column) implements Change {}

    record ColumnDropped(QualifiedName table, Column column) implements Change {}

    record ColumnTypeChanged(QualifiedName table, String column, DataType before, DataType after) implements Change {}

    record ColumnCollationChanged(QualifiedName table, String column, Optional<String> before, Optional<String> after)
            implements Change {}

    /** @param notNull the new setting */
    record ColumnNullabilityChanged(QualifiedName table, String column, boolean notNull) implements Change {}

    record ColumnDefaultChanged(
            QualifiedName table,
            String column,
            Optional<Expression> before,
            Optional<Expression> after,
            boolean cosmetic)
            implements Change {}

    record ColumnIdentityChanged(QualifiedName table, String column, Identity before, Identity after)
            implements Change {}

    record ColumnGeneratedChanged(
            QualifiedName table,
            String column,
            Optional<GeneratedColumn> before,
            Optional<GeneratedColumn> after,
            boolean cosmetic)
            implements Change {}

    /** The columns that exist in both versions, listed in their order before and after. */
    record ColumnOrderChanged(QualifiedName table, List<String> before, List<String> after) implements Change {
        public ColumnOrderChanged {
            before = List.copyOf(before);
            after = List.copyOf(after);
        }
    }

    record ColumnRenameCandidate(QualifiedName table, String from, String to, double confidence, List<String> evidence)
            implements Change {
        public ColumnRenameCandidate {
            evidence = List.copyOf(evidence);
        }
    }

    // ---------------------------------------------------------------- constraints

    record ConstraintAdded(QualifiedName table, Constraint constraint) implements Change {}

    record ConstraintDropped(QualifiedName table, Constraint constraint) implements Change {}

    /** Same definition under a new name. */
    record ConstraintRenamed(QualifiedName table, String from, String to) implements Change {}

    /** Same name, different definition. */
    record ConstraintChanged(QualifiedName table, Constraint before, Constraint after, boolean cosmetic)
            implements Change {}

    /** A constraint added as NOT VALID has since been checked against the existing rows. */
    record ConstraintValidated(QualifiedName table, String constraint) implements Change {}

    // ---------------------------------------------------------------- indexes

    record IndexAdded(Index index) implements Change {}

    record IndexDropped(Index index) implements Change {}

    /** Same definition under a new name. An index always lives in its table's schema. */
    record IndexRenamed(QualifiedName table, String from, String to) implements Change {}

    /** Same name and table, different definition. */
    record IndexChanged(Index before, Index after, boolean cosmetic) implements Change {}

    // ---------------------------------------------------------------- sequences

    record SequenceAdded(Sequence sequence) implements Change {}

    record SequenceDropped(Sequence sequence) implements Change {}

    /** @param properties the settings that differ, by SQL keyword, in a fixed order */
    record SequenceChanged(Sequence before, Sequence after, List<String> properties) implements Change {
        public SequenceChanged {
            properties = List.copyOf(properties);
        }
    }
}
