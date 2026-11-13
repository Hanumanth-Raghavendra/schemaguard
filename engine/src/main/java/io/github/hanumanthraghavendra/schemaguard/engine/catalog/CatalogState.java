package io.github.hanumanthraghavendra.schemaguard.engine.catalog;

import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Constraint;
import io.github.hanumanthraghavendra.schemaguard.engine.model.EnumType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Index;
import io.github.hanumanthraghavendra.schemaguard.engine.model.PrimaryKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Sequence;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Table;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Unique;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The schema as it stands partway through a script. Mutable, unlike the {@link Catalog} it
 * ends as; the values it holds are all immutable, so a copy is cheap and safe to restore.
 */
final class CatalogState {

    final TreeMap<QualifiedName, Table> tables = new TreeMap<>();
    final TreeMap<QualifiedName, EnumType> enums = new TreeMap<>();
    final TreeMap<QualifiedName, Sequence> sequences = new TreeMap<>();
    final TreeMap<QualifiedName, Index> indexes = new TreeMap<>();

    CatalogState copy() {
        CatalogState copy = new CatalogState();
        copy.restore(this);
        return copy;
    }

    void restore(CatalogState saved) {
        tables.clear();
        tables.putAll(saved.tables);
        enums.clear();
        enums.putAll(saved.enums);
        sequences.clear();
        sequences.putAll(saved.sequences);
        indexes.clear();
        indexes.putAll(saved.indexes);
    }

    /** The table of that name, or an error worded as PostgreSQL words it. */
    Table table(QualifiedName name) {
        Table table = tables.get(name);
        if (table == null) {
            throw CatalogError.undefinedObject("relation \"" + name.name() + "\" does not exist");
        }
        return table;
    }

    /**
     * Tables, sequences and indexes share one namespace per schema, and every primary key or
     * unique constraint owns an index named after it. A name is taken if any of them has it.
     */
    boolean relationExists(QualifiedName name) {
        return tables.containsKey(name)
                || sequences.containsKey(name)
                || indexes.containsKey(name)
                || constraintIndexOwner(name).isPresent();
    }

    /** The table whose primary key or unique constraint is backed by an index of this name. */
    Optional<Table> constraintIndexOwner(QualifiedName name) {
        return tables.values().stream()
                .filter(t -> t.name().schema().equals(name.schema()))
                .filter(t -> t.constraint(name.name()).filter(CatalogState::isKey).isPresent())
                .findFirst();
    }

    static boolean isKey(Constraint constraint) {
        return constraint instanceof PrimaryKey || constraint instanceof Unique;
    }

    /** Freezes the state. Constraints are put in name order: the order they were added in is not part of a schema. */
    Catalog toCatalog() {
        TreeMap<QualifiedName, Table> ordered = new TreeMap<>();
        tables.forEach(
                (name, table) -> {
                    List<Constraint> constraints =
                            table.constraints().stream()
                                    .sorted(Comparator.comparing(c -> c.name().orElse("")))
                                    .toList();
                    ordered.put(name, table.withConstraints(constraints));
                });
        return new Catalog(ordered, enums, sequences, indexes);
    }
}
