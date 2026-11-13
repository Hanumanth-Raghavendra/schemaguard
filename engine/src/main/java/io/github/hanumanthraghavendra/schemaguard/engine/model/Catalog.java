package io.github.hanumanthraghavendra.schemaguard.engine.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A snapshot of a database schema: every table, enumerated type, sequence and index, each
 * under its qualified name. An index is keyed by its table's schema and its own name.
 *
 * <p>Immutable, and the maps iterate in name order, so two catalogs built from the same schema
 * are equal and print identically however the schema was written.
 */
public record Catalog(
        Map<QualifiedName, Table> tables,
        Map<QualifiedName, EnumType> enums,
        Map<QualifiedName, Sequence> sequences,
        Map<QualifiedName, Index> indexes) {

    public Catalog {
        tables = sorted(tables);
        enums = sorted(enums);
        sequences = sorted(sequences);
        indexes = sorted(indexes);
    }

    private static <V> Map<QualifiedName, V> sorted(Map<QualifiedName, V> map) {
        return Collections.unmodifiableSortedMap(new TreeMap<>(map));
    }

    public static Catalog empty() {
        return new Catalog(Map.of(), Map.of(), Map.of(), Map.of());
    }

    public Optional<Table> table(QualifiedName name) {
        return Optional.ofNullable(tables.get(name));
    }

    /** The indexes on the given table, in name order. Constraint-backed indexes are not listed. */
    public List<Index> indexesOf(QualifiedName table) {
        return indexes.values().stream().filter(i -> i.table().equals(table)).toList();
    }
}
