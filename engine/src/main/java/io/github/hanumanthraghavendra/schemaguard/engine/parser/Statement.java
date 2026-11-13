package io.github.hanumanthraghavendra.schemaguard.engine.parser;

import io.github.hanumanthraghavendra.schemaguard.engine.model.ColumnRef;
import io.github.hanumanthraghavendra.schemaguard.engine.model.EnumType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Index;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ObjectKind;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Sequence;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Table;
import io.github.hanumanthraghavendra.schemaguard.engine.source.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One parsed SQL statement.
 *
 * <p>Every statement in the input yields exactly one of these, so nothing is dropped without
 * a trace: what the parser cannot analyse becomes {@link Unsupported} or {@link Invalid}.
 */
public sealed interface Statement {

    /** Where the statement sits in the source, excluding its terminating semicolon. */
    SourceSpan span();

    record CreateTable(Table table, boolean ifNotExists, SourceSpan span) implements Statement {
        public CreateTable {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(span, "span");
        }
    }

    record AlterTable(
            QualifiedName table,
            boolean ifExists,
            boolean only,
            List<AlterAction> actions,
            SourceSpan span)
            implements Statement {
        public AlterTable {
            Objects.requireNonNull(table, "table");
            actions = List.copyOf(actions);
        }
    }

    /** @param only true for {@code ON ONLY table}: do not recurse into partitions */
    record CreateIndex(
            Index index, boolean concurrently, boolean ifNotExists, boolean only, SourceSpan span)
            implements Statement {
        public CreateIndex {
            Objects.requireNonNull(index, "index");
        }
    }

    record CreateEnum(EnumType type, SourceSpan span) implements Statement {}

    /** {@code ALTER TYPE ... ADD VALUE}. At most one of {@code before} and {@code after} is set. */
    record AddEnumValue(
            QualifiedName type,
            String value,
            boolean ifNotExists,
            Optional<String> before,
            Optional<String> after,
            SourceSpan span)
            implements Statement {}

    record RenameEnumValue(QualifiedName type, String from, String to, SourceSpan span)
            implements Statement {}

    record CreateSequence(Sequence sequence, boolean ifNotExists, SourceSpan span)
            implements Statement {}

    /** {@code ALTER SEQUENCE ... OWNED BY}. An empty owner means {@code OWNED BY NONE}. */
    record AlterSequenceOwner(
            QualifiedName sequence, boolean ifExists, Optional<ColumnRef> ownedBy, SourceSpan span)
            implements Statement {}

    /** @param concurrently only ever true for {@code DROP INDEX CONCURRENTLY} */
    record Drop(
            ObjectKind kind,
            List<QualifiedName> names,
            boolean ifExists,
            boolean cascade,
            boolean concurrently,
            SourceSpan span)
            implements Statement {
        public Drop {
            Objects.requireNonNull(kind, "kind");
            names = List.copyOf(names);
        }
    }

    /**
     * A statement that is recognised and deliberately not modelled because it does not change
     * the schema's structure: {@code SET}, {@code COMMENT}, {@code GRANT}, ownership changes.
     *
     * @param kind the statement's leading keywords, for example {@code SET}
     */
    record Ignored(String kind, SourceSpan span) implements Statement {}

    /**
     * A valid statement the parser does not handle yet. A warning diagnostic accompanies it.
     *
     * @param kind the statement's leading keywords, for example {@code CREATE FUNCTION}
     */
    record Unsupported(String kind, SourceSpan span) implements Statement {}

    /** A statement with a syntax error. An error diagnostic accompanies it. */
    record Invalid(SourceSpan span) implements Statement {}
}
