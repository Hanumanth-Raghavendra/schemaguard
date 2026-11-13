package io.github.hanumanthraghavendra.schemaguard.engine.diff;

import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import java.util.List;
import java.util.Objects;

/**
 * Everything that differs between two schemas, with the two catalogs it was computed from.
 * Later stages need both: the risk rules judge a change by what surrounds it (adding an index
 * to a table created in the same migration is harmless), and the planner reads the details.
 */
public record SchemaDiff(Catalog before, Catalog after, List<Change> changes) {

    public SchemaDiff {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        changes = List.copyOf(changes);
    }

    public static SchemaDiff compare(Catalog before, Catalog after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        return new SchemaDiff(before, after, Differ.compare(before, after));
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    /** The changes that are not merely cosmetic. */
    public List<Change> significant() {
        return changes.stream().filter(c -> !c.cosmetic()).toList();
    }
}
