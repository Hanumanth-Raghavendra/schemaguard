package io.github.hanumanthraghavendra.schemaguard.engine.diff;

import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Check;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Column;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Constraint;
import io.github.hanumanthraghavendra.schemaguard.engine.model.EnumType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ForeignKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Index;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Sequence;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Table;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Compares two catalogs. Objects are matched by name first; what is left is matched by
 * definition, so that an index or constraint under a new name is a rename and not a drop
 * plus an add. Tables, columns and enum values cannot be matched that way with certainty, so
 * for those the diff reports the drop, the add, and a scored rename candidate.
 *
 * <p>Output order is fixed: enums, then tables in name order (each followed by its columns,
 * constraints and indexes), then table rename candidates, then sequences.
 */
final class Differ {

    /** Rename candidates below this confidence are not reported. */
    static final double MIN_CONFIDENCE = 0.5;

    private final Catalog before;
    private final Catalog after;
    private final List<Change> changes = new ArrayList<>();

    private Differ(Catalog before, Catalog after) {
        this.before = before;
        this.after = after;
    }

    static List<Change> compare(Catalog before, Catalog after) {
        Differ differ = new Differ(before, after);
        differ.enums();
        Map<QualifiedName, List<Change>> indexChanges = differ.indexes();
        differ.tables(indexChanges);
        differ.tableRenameCandidates();
        differ.sequences();
        return differ.changes;
    }

    // ---------------------------------------------------------------- enumerated types

    private void enums() {
        for (QualifiedName name : union(before.enums().keySet(), after.enums().keySet())) {
            EnumType old = before.enums().get(name);
            EnumType now = after.enums().get(name);
            if (old == null) {
                changes.add(new Change.EnumAdded(now));
            } else if (now == null) {
                changes.add(new Change.EnumDropped(old));
            } else {
                enumValues(name, old.labels(), now.labels());
            }
        }
    }

    private void enumValues(QualifiedName type, List<String> old, List<String> now) {
        if (old.equals(now)) {
            return;
        }
        Set<String> oldSet = new HashSet<>(old);
        Set<String> nowSet = new HashSet<>(now);
        for (String value : old) {
            if (!nowSet.contains(value)) {
                changes.add(new Change.EnumValueRemoved(type, value));
            }
        }
        for (int i = 0; i < now.size(); i++) {
            String value = now.get(i);
            if (!oldSet.contains(value)) {
                // The next value that already existed: adding in this order with
                // ADD VALUE ... BEFORE that value reproduces the new order.
                Optional<String> next = now.subList(i + 1, now.size()).stream().filter(oldSet::contains).findFirst();
                changes.add(new Change.EnumValueAdded(type, value, next));
            }
        }
        List<String> keptOld = old.stream().filter(nowSet::contains).toList();
        List<String> keptNow = now.stream().filter(oldSet::contains).toList();
        if (!keptOld.equals(keptNow)) {
            changes.add(new Change.EnumValuesReordered(type, old, now));
            return;
        }

        // Between two values that both versions share, a removed value and an added value in the
        // same place may be one value renamed.
        List<List<String>> removedGaps = gaps(old, nowSet);
        List<List<String>> addedGaps = gaps(now, oldSet);
        List<Candidate<String>> candidates = new ArrayList<>();
        for (int g = 0; g < removedGaps.size(); g++) {
            List<String> removed = removedGaps.get(g);
            List<String> added = addedGaps.get(g);
            if (removed.isEmpty() || removed.size() != added.size()) {
                continue;
            }
            for (int i = 0; i < removed.size(); i++) {
                Similarity.Score score = Similarity.of(removed.get(i), added.get(i));
                candidates.add(
                        new Candidate<>(
                                removed.get(i),
                                added.get(i),
                                0.5 + 0.5 * score.value(),
                                List.of("takes the place of the removed value", score.explanation())));
            }
        }
        for (Candidate<String> c : pick(candidates)) {
            changes.add(new Change.EnumValueRenameCandidate(type, c.from(), c.to(), c.confidence(), c.evidence()));
        }
    }

    /** Splits the values that are not in {@code shared} into runs separated by shared values. */
    private static List<List<String>> gaps(List<String> values, Set<String> shared) {
        List<List<String>> gaps = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String value : values) {
            if (shared.contains(value)) {
                gaps.add(current);
                current = new ArrayList<>();
            } else {
                current.add(value);
            }
        }
        gaps.add(current);
        return gaps;
    }

    // ---------------------------------------------------------------- tables

    private void tables(Map<QualifiedName, List<Change>> indexChanges) {
        for (QualifiedName name : union(before.tables().keySet(), after.tables().keySet())) {
            Table old = before.tables().get(name);
            Table now = after.tables().get(name);
            if (old == null) {
                changes.add(new Change.TableAdded(now));
            } else if (now == null) {
                changes.add(new Change.TableDropped(old));
            } else {
                tableProperties(name, old, now);
                columns(name, old, now);
                constraints(name, old, now);
            }
            changes.addAll(indexChanges.getOrDefault(name, List.of()));
        }
    }

    private void tableProperties(QualifiedName name, Table old, Table now) {
        if (old.persistence() != now.persistence()) {
            changes.add(new Change.TablePersistenceChanged(name, old.persistence(), now.persistence()));
        }
        if (!old.partitionBy().equals(now.partitionBy())) {
            changes.add(new Change.TablePartitioningChanged(name, old.partitionBy(), now.partitionBy()));
        }
        if (!old.inherits().equals(now.inherits())) {
            changes.add(new Change.TableInheritanceChanged(name, old.inherits(), now.inherits()));
        }
    }

    private void tableRenameCandidates() {
        List<Table> dropped =
                before.tables().values().stream().filter(t -> !after.tables().containsKey(t.name())).toList();
        List<Table> added =
                after.tables().values().stream().filter(t -> !before.tables().containsKey(t.name())).toList();
        List<Candidate<QualifiedName>> candidates = new ArrayList<>();
        for (Table old : dropped) {
            List<Column> columns = normalizedColumns(old);
            for (Table now : added) {
                if (columns.isEmpty() || !columns.equals(normalizedColumns(now))) {
                    continue;
                }
                Similarity.Score score = Similarity.of(old.name().name(), now.name().name());
                List<String> evidence = new ArrayList<>();
                evidence.add(columns.size() == 1 ? "same column" : "same " + columns.size() + " columns");
                evidence.add(score.explanation());
                if (!old.name().schema().equals(now.name().schema())) {
                    evidence.add("moved from schema " + old.name().schema() + " to " + now.name().schema());
                }
                candidates.add(new Candidate<>(old.name(), now.name(), 0.5 + 0.5 * score.value(), evidence));
            }
        }
        for (Candidate<QualifiedName> c : pick(candidates)) {
            changes.add(new Change.TableRenameCandidate(c.from(), c.to(), c.confidence(), c.evidence()));
        }
    }

    private static List<Column> normalizedColumns(Table table) {
        return table.columns().stream().map(Normalized::column).toList();
    }

    // ---------------------------------------------------------------- columns

    private void columns(QualifiedName table, Table old, Table now) {
        List<Column> added = new ArrayList<>();
        List<Column> dropped = new ArrayList<>();
        for (Column column : now.columns()) {
            Optional<Column> previous = old.column(column.name());
            if (previous.isPresent()) {
                columnAttributes(table, previous.get(), column);
            } else {
                added.add(column);
                changes.add(new Change.ColumnAdded(table, column));
            }
        }
        for (Column column : old.columns()) {
            if (now.column(column.name()).isEmpty()) {
                dropped.add(column);
                changes.add(new Change.ColumnDropped(table, column));
            }
        }
        List<String> orderBefore =
                old.columns().stream().map(Column::name).filter(n -> now.column(n).isPresent()).toList();
        List<String> orderAfter =
                now.columns().stream().map(Column::name).filter(n -> old.column(n).isPresent()).toList();
        if (!orderBefore.equals(orderAfter)) {
            changes.add(new Change.ColumnOrderChanged(table, orderBefore, orderAfter));
        }
        columnRenameCandidates(table, old, now, dropped, added);
    }

    private void columnAttributes(QualifiedName table, Column old, Column now) {
        String name = now.name();
        if (!old.type().equals(now.type())) {
            changes.add(new Change.ColumnTypeChanged(table, name, old.type(), now.type()));
        }
        if (!old.collation().equals(now.collation())) {
            changes.add(new Change.ColumnCollationChanged(table, name, old.collation(), now.collation()));
        }
        if (old.notNull() != now.notNull()) {
            changes.add(new Change.ColumnNullabilityChanged(table, name, now.notNull()));
        }
        if (!old.defaultValue().equals(now.defaultValue())) {
            boolean cosmetic =
                    old.defaultValue().isPresent()
                            && now.defaultValue().isPresent()
                            && Normalized.expression(old.defaultValue().get())
                                    .equals(Normalized.expression(now.defaultValue().get()));
            changes.add(new Change.ColumnDefaultChanged(table, name, old.defaultValue(), now.defaultValue(), cosmetic));
        }
        if (old.identity() != now.identity()) {
            changes.add(new Change.ColumnIdentityChanged(table, name, old.identity(), now.identity()));
        }
        if (!old.generated().equals(now.generated())) {
            boolean cosmetic =
                    old.generated().isPresent()
                            && now.generated().isPresent()
                            && Normalized.column(old).generated().equals(Normalized.column(now).generated());
            changes.add(new Change.ColumnGeneratedChanged(table, name, old.generated(), now.generated(), cosmetic));
        }
    }

    /**
     * A dropped and an added column with the same definition may be one column renamed. The
     * score rewards similar names and the same position: PostgreSQL appends new columns at the
     * end, so a renamed column keeps its place and a replacement usually does not.
     */
    private void columnRenameCandidates(
            QualifiedName table, Table old, Table now, List<Column> dropped, List<Column> added) {
        List<Candidate<String>> candidates = new ArrayList<>();
        for (Column gone : dropped) {
            Column goneShape = Normalized.column(gone);
            int oldPosition = old.columns().indexOf(gone);
            for (Column fresh : added) {
                if (!goneShape.withName(fresh.name()).equals(Normalized.column(fresh))) {
                    continue;
                }
                Similarity.Score score = Similarity.of(gone.name(), fresh.name());
                boolean samePosition = oldPosition == now.columns().indexOf(fresh);
                List<String> evidence = new ArrayList<>(List.of("same type and attributes", score.explanation()));
                if (samePosition) {
                    evidence.add("both are column " + (oldPosition + 1));
                }
                double confidence = 0.7 * score.value() + (samePosition ? 0.3 : 0.0);
                candidates.add(new Candidate<>(gone.name(), fresh.name(), confidence, evidence));
            }
        }
        for (Candidate<String> c : pick(candidates)) {
            changes.add(new Change.ColumnRenameCandidate(table, c.from(), c.to(), c.confidence(), c.evidence()));
        }
    }

    // ---------------------------------------------------------------- constraints

    private void constraints(QualifiedName table, Table old, Table now) {
        Map<String, Constraint> oldByName = byName(old.constraints());
        Map<String, Constraint> nowByName = byName(now.constraints());
        List<Constraint> oldOnly = new ArrayList<>();
        List<Constraint> newOnly = new ArrayList<>();
        for (String name : union(oldByName.keySet(), nowByName.keySet())) {
            Constraint o = oldByName.get(name);
            Constraint n = nowByName.get(name);
            if (o != null && n != null) {
                compareConstraints(table, o, n);
            } else if (o != null) {
                oldOnly.add(o);
            } else {
                newOnly.add(n);
            }
        }

        List<Match<Constraint>> renamed = new ArrayList<>(match(oldOnly, newOnly, Differ::shape));
        renamed.addAll(match(oldOnly, newOnly, c -> Normalized.constraint(shape(c))));
        for (Match<Constraint> m : renamed) {
            changes.add(new Change.ConstraintRenamed(table, nameOf(m.before()), nameOf(m.after())));
            compareConstraints(table, m.before().withName(m.after().name()), m.after());
        }
        oldOnly.forEach(c -> changes.add(new Change.ConstraintDropped(table, c)));
        newOnly.forEach(c -> changes.add(new Change.ConstraintAdded(table, c)));
    }

    private void compareConstraints(QualifiedName table, Constraint old, Constraint now) {
        if (old.equals(now)) {
            return;
        }
        boolean validated = notValid(old) && !notValid(now);
        if (validated) {
            changes.add(new Change.ConstraintValidated(table, nameOf(now)));
        }
        Constraint oldShape = clearNotValid(old);
        Constraint nowShape = clearNotValid(now);
        if (validated && oldShape.equals(nowShape)) {
            return;
        }
        // A valid constraint cannot become NOT VALID again in place, so that is never cosmetic.
        boolean invalidated = !notValid(old) && notValid(now);
        boolean cosmetic =
                !invalidated && Normalized.constraint(oldShape).equals(Normalized.constraint(nowShape));
        changes.add(new Change.ConstraintChanged(table, old, now, cosmetic));
    }

    /** The definition alone: no name, and NOT VALID cleared. */
    private static Constraint shape(Constraint constraint) {
        return clearNotValid(constraint).withName(Optional.empty());
    }

    private static boolean notValid(Constraint constraint) {
        return switch (constraint) {
            case ForeignKey fk -> fk.notValid();
            case Check check -> check.notValid();
            default -> false;
        };
    }

    private static Constraint clearNotValid(Constraint constraint) {
        return switch (constraint) {
            case ForeignKey fk -> fk.withNotValid(false);
            case Check check -> check.withNotValid(false);
            default -> constraint;
        };
    }

    private static Map<String, Constraint> byName(List<Constraint> constraints) {
        Map<String, Constraint> map = new TreeMap<>();
        constraints.forEach(c -> map.put(nameOf(c), c));
        return map;
    }

    private static String nameOf(Constraint constraint) {
        return constraint.name().orElse("");
    }

    // ---------------------------------------------------------------- indexes

    /** Index changes, grouped under the table each one concerns, in a fixed order. */
    private Map<QualifiedName, List<Change>> indexes() {
        Map<QualifiedName, List<Change>> byTable = new HashMap<>();
        List<Index> oldOnly = new ArrayList<>();
        List<Index> newOnly = new ArrayList<>();
        for (QualifiedName key : union(before.indexes().keySet(), after.indexes().keySet())) {
            Index old = before.indexes().get(key);
            Index now = after.indexes().get(key);
            if (old != null && now != null && old.table().equals(now.table())) {
                if (!old.equals(now)) {
                    boolean cosmetic = Normalized.index(old).equals(Normalized.index(now));
                    add(byTable, now.table(), new Change.IndexChanged(old, now, cosmetic));
                }
            } else {
                // An index cannot move to another table, so a name reused on another table is a new index.
                if (old != null) {
                    oldOnly.add(old);
                }
                if (now != null) {
                    newOnly.add(now);
                }
            }
        }

        Function<Index, Object> exact = i -> i.withName(Optional.empty());
        List<Match<Index>> renamed = new ArrayList<>(match(oldOnly, newOnly, exact));
        renamed.addAll(match(oldOnly, newOnly, i -> Normalized.index(i.withName(Optional.empty()))));
        for (Match<Index> m : renamed) {
            QualifiedName table = m.after().table();
            add(byTable, table, new Change.IndexRenamed(table, nameOf(m.before()), nameOf(m.after())));
            Index moved = m.before().withName(m.after().name());
            if (!moved.equals(m.after())) {
                add(byTable, table, new Change.IndexChanged(moved, m.after(), true));
            }
        }
        oldOnly.forEach(i -> add(byTable, i.table(), new Change.IndexDropped(i)));
        newOnly.forEach(i -> add(byTable, i.table(), new Change.IndexAdded(i)));
        return byTable;
    }

    private static String nameOf(Index index) {
        return index.name().orElse("");
    }

    private static void add(Map<QualifiedName, List<Change>> byTable, QualifiedName table, Change change) {
        byTable.computeIfAbsent(table, t -> new ArrayList<>()).add(change);
    }

    // ---------------------------------------------------------------- sequences

    private void sequences() {
        for (QualifiedName name : union(before.sequences().keySet(), after.sequences().keySet())) {
            Sequence old = before.sequences().get(name);
            Sequence now = after.sequences().get(name);
            if (old == null) {
                changes.add(new Change.SequenceAdded(now));
            } else if (now == null) {
                changes.add(new Change.SequenceDropped(old));
            } else if (!old.equals(now)) {
                changes.add(new Change.SequenceChanged(old, now, sequenceProperties(old, now)));
            }
        }
    }

    private static List<String> sequenceProperties(Sequence old, Sequence now) {
        List<String> properties = new ArrayList<>();
        addIf(properties, "persistence", old.persistence(), now.persistence());
        addIf(properties, "type", old.dataType(), now.dataType());
        addIf(properties, "start", old.start(), now.start());
        addIf(properties, "increment", old.increment(), now.increment());
        addIf(properties, "minvalue", old.minValue(), now.minValue());
        addIf(properties, "maxvalue", old.maxValue(), now.maxValue());
        addIf(properties, "cache", old.cache(), now.cache());
        addIf(properties, "cycle", old.cycle(), now.cycle());
        addIf(properties, "owned by", old.ownedBy(), now.ownedBy());
        return properties;
    }

    private static void addIf(List<String> properties, String property, Object old, Object now) {
        if (!Objects.equals(old, now)) {
            properties.add(property);
        }
    }

    // ---------------------------------------------------------------- matching

    private record Match<T>(T before, T after) {}

    /**
     * Pairs leftover objects whose shapes are equal, removing each pair from both lists. Lists
     * are walked in order, so the pairing is deterministic.
     */
    private static <T> List<Match<T>> match(List<T> olds, List<T> news, Function<T, ?> shape) {
        List<Match<T>> matches = new ArrayList<>();
        for (Iterator<T> it = news.iterator(); it.hasNext(); ) {
            T now = it.next();
            Object key = shape.apply(now);
            for (int i = 0; i < olds.size(); i++) {
                if (shape.apply(olds.get(i)).equals(key)) {
                    matches.add(new Match<>(olds.remove(i), now));
                    it.remove();
                    break;
                }
            }
        }
        return matches;
    }

    private record Candidate<K extends Comparable<K>>(K from, K to, double confidence, List<String> evidence) {
        Candidate {
            confidence = Math.round(confidence * 100) / 100.0;
        }
    }

    /**
     * Keeps the strongest candidates such that no name is used twice: sorted by confidence, then
     * by name, and taken greedily.
     */
    private static <K extends Comparable<K>> List<Candidate<K>> pick(List<Candidate<K>> candidates) {
        Comparator<Candidate<K>> order =
                Comparator.<Candidate<K>>comparingDouble(c -> -c.confidence())
                        .thenComparing(c -> c.from())
                        .thenComparing(c -> c.to());
        Set<K> usedFrom = new HashSet<>();
        Set<K> usedTo = new HashSet<>();
        List<Candidate<K>> picked = new ArrayList<>();
        for (Candidate<K> c : candidates.stream().filter(c -> c.confidence() >= MIN_CONFIDENCE).sorted(order).toList()) {
            if (!usedFrom.contains(c.from()) && !usedTo.contains(c.to())) {
                usedFrom.add(c.from());
                usedTo.add(c.to());
                picked.add(c);
            }
        }
        return picked;
    }

    private static <K extends Comparable<K>> SortedSet<K> union(Set<K> a, Set<K> b) {
        SortedSet<K> all = new TreeSet<>(a);
        all.addAll(b);
        return all;
    }
}
