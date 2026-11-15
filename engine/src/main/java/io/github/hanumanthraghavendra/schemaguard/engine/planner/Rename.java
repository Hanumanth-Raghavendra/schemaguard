package io.github.hanumanthraghavendra.schemaguard.engine.planner;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import java.util.Optional;

/**
 * A rename that a person has confirmed.
 *
 * <p>Two schemas cannot tell a rename from a drop followed by an add, so the diff only
 * offers candidates. Confirming one tells the planner to write {@code RENAME}, which keeps
 * the data, where it would otherwise write a drop and an add.
 */
public sealed interface Rename {

    record OfTable(QualifiedName from, QualifiedName to) implements Rename {}

    record OfColumn(QualifiedName table, String from, String to) implements Rename {}

    record OfEnumValue(QualifiedName type, String from, String to) implements Rename {}

    /** The rename a candidate from the diff proposes; empty for every other kind of change. */
    static Optional<Rename> of(Change candidate) {
        return switch (candidate) {
            case Change.TableRenameCandidate c -> Optional.of(new OfTable(c.from(), c.to()));
            case Change.ColumnRenameCandidate c -> Optional.of(new OfColumn(c.table(), c.from(), c.to()));
            case Change.EnumValueRenameCandidate c -> Optional.of(new OfEnumValue(c.type(), c.from(), c.to()));
            default -> Optional.empty();
        };
    }
}
