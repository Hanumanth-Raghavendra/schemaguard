package io.github.hanumanthraghavendra.schemaguard.engine.planner;

import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogBuilder;
import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogResult;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.SchemaDiff;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Check;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Column;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ColumnRef;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Constraint;
import io.github.hanumanthraghavendra.schemaguard.engine.model.EnumType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Expression;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ForeignKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Identity;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Index;
import io.github.hanumanthraghavendra.schemaguard.engine.model.IndexColumn;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Persistence;
import io.github.hanumanthraghavendra.schemaguard.engine.model.PrimaryKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Sequence;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Table;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Unique;
import io.github.hanumanthraghavendra.schemaguard.engine.planner.PlanOptions.Route;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.DirectRoute;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskAnalyzer;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.TypeChange;
import io.github.hanumanthraghavendra.schemaguard.engine.sql.SqlWriter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Writes the migration that turns one schema into another.
 *
 * <pre>{@code
 * MigrationPlan plan = Planner.plan(before, after, PlanOptions.safe());
 * System.out.print(plan.toSql());
 * }</pre>
 *
 * <p>The planner works in two moves. Each change becomes one or more statements, and each
 * statement is put into a {@link Stage}. The stages then run in a fixed order that satisfies
 * every dependency: a foreign key is dropped before the key it points to, a column is made
 * NOT NULL before it becomes part of a primary key, a sequence is created before the column
 * whose default uses it.
 *
 * <p>On the safe route a statement that would block the application is replaced by the
 * slower, non-blocking way of reaching the same result, but only where the table can have
 * rows. A table created by the migration itself is empty, and the plain statement is used.
 */
public final class Planner {

    /** The order in which statements run. Within a stage they keep the order of the diff. */
    private enum Stage {
        SETTINGS,
        CONFIRMED_RENAMES,
        TYPES,
        DROP_IDENTITY,
        SEQUENCES,
        DROP_FOREIGN_KEYS,
        DROP_TABLES,
        DROP_CONSTRAINTS,
        DROP_INDEXES,
        RENAMES,
        CREATE_TABLES,
        ADD_COLUMNS,
        RELEASE_COLUMNS,
        COLUMN_TYPES,
        NOT_NULL,
        IDENTITY,
        DEFAULTS,
        NULLABLE,
        TABLE_OPTIONS,
        DROP_COLUMNS,
        KEYS,
        FOREIGN_KEYS,
        INDEXES,
        VALIDATE,
        SWAP,
        SEQUENCE_OPTIONS,
        DROP_SEQUENCES,
        DROP_TYPES
    }

    /** Rules whose risk the safe route removes, so their findings need no caution. */
    private static final Set<String> SOLVED_BY_SAFE_ROUTE =
            Set.of("SG-R101", "SG-R102", "SG-R103", "SG-R104", "SG-R105", "SG-R106", "SG-R107", "SG-R108", "SG-R109", "SG-R111");

    private final PlanOptions options;
    private final Map<Stage, List<Step>> stages = new EnumMap<>(Stage.class);
    private final List<MigrationPlan.Unplanned> unplanned = new ArrayList<>();
    private final Set<String> usedNames = new HashSet<>();
    private final Set<ColumnRef> retyped = new HashSet<>();
    private SchemaDiff diff;
    private RuleContext context;
    private Change current;

    private Planner(PlanOptions options) {
        this.options = options;
    }

    /** The plan for the direct route. */
    public static MigrationPlan plan(Catalog before, Catalog after) {
        return plan(before, after, PlanOptions.direct());
    }

    /**
     * @throws IllegalArgumentException if a confirmed rename does not fit the old schema, for
     *     example because the table it names does not exist
     */
    public static MigrationPlan plan(Catalog before, Catalog after, PlanOptions options) {
        return new Planner(options).build(before, after);
    }

    private MigrationPlan build(Catalog before, Catalog after) {
        diff = SchemaDiff.compare(applyRenames(before), after);
        context = new RuleContext(diff);
        for (Catalog catalog : List.of(diff.before(), diff.after())) {
            catalog.indexes().keySet().forEach(name -> usedNames.add(name.name()));
            catalog.tables().values().forEach(t -> t.constraints().forEach(c -> c.name().ifPresent(usedNames::add)));
        }

        for (Change change : diff.changes()) {
            if (!change.cosmetic()) {
                current = change;
                plan(change);
            }
        }
        current = null;
        addEnumValues();
        dropTables();

        List<Step> steps = new ArrayList<>();
        stages.values().forEach(steps::addAll);
        if (options.route() == Route.SAFE && !steps.isEmpty()) {
            steps.add(
                    0,
                    new Step(
                            "SET lock_timeout = '" + options.lockTimeout() + "'",
                            Optional.empty(),
                            Optional.empty(),
                            true,
                            "Give up instead of queueing behind a long query; run the script again if it times out",
                            List.of(),
                            Optional.empty()));
            steps = withCautions(steps);
        }
        return new MigrationPlan(options.route(), diff, steps, unplanned);
    }

    // ---------------------------------------------------------------- confirmed renames

    /** Writes the confirmed renames as the first steps and returns the old schema with them applied. */
    private Catalog applyRenames(Catalog before) {
        if (options.renames().isEmpty()) {
            return before;
        }
        StringBuilder script = new StringBuilder(SqlWriter.script(before));
        for (Rename rename : options.renames()) {
            for (Step step : renameSteps(rename)) {
                stages.computeIfAbsent(Stage.CONFIRMED_RENAMES, stage -> new ArrayList<>()).add(step);
                script.append(step.sql()).append(";\n");
            }
        }
        CatalogResult renamed = CatalogBuilder.fromSql(script.toString());
        if (renamed.hasErrors()) {
            throw new IllegalArgumentException(
                    "A confirmed rename does not fit the old schema: " + renamed.diagnostics().get(0).message());
        }
        return renamed.catalog();
    }

    private static List<Step> renameSteps(Rename rename) {
        String note = "Confirmed rename: the data is kept";
        return switch (rename) {
            case Rename.OfTable r -> {
                List<Step> steps = new ArrayList<>();
                QualifiedName name = r.from();
                if (!name.name().equals(r.to().name())) {
                    steps.add(step("ALTER TABLE " + name.toSql() + " RENAME TO " + SqlWriter.name(r.to().name()), Operation.RENAME_TABLE, name, note));
                    name = new QualifiedName(name.schema(), r.to().name());
                }
                if (!name.schema().equals(r.to().schema())) {
                    steps.add(step("ALTER TABLE " + name.toSql() + " SET SCHEMA " + SqlWriter.name(r.to().schema()), Operation.SET_SCHEMA, name, note));
                }
                yield steps;
            }
            case Rename.OfColumn r ->
                    List.of(
                            step(
                                    "ALTER TABLE " + r.table().toSql() + " RENAME COLUMN " + SqlWriter.name(r.from()) + " TO " + SqlWriter.name(r.to()),
                                    Operation.RENAME_COLUMN,
                                    r.table(),
                                    note));
            case Rename.OfEnumValue r ->
                    List.of(
                            step(
                                    "ALTER TYPE " + r.type().toSql() + " RENAME VALUE " + SqlWriter.literal(r.from()) + " TO " + SqlWriter.literal(r.to()),
                                    Operation.RENAME_ENUM_VALUE,
                                    null,
                                    note));
        };
    }

    private static Step step(String sql, Operation operation, QualifiedName table, String note) {
        return new Step(sql, Optional.ofNullable(operation), Optional.ofNullable(table), true, note, List.of(), Optional.empty());
    }

    // ---------------------------------------------------------------- one change at a time

    private void plan(Change change) {
        switch (change) {
            case Change.EnumAdded c -> add(Stage.TYPES, SqlWriter.createEnum(c.type()), null, null);
            case Change.EnumDropped c -> add(Stage.DROP_TYPES, "DROP TYPE " + c.type().name().toSql(), null, null);
            case Change.EnumValueAdded c -> {
                // Planned for the whole type at once, see addEnumValues().
            }
            case Change.EnumValueRemoved c ->
                    leaveOut(
                            "PostgreSQL cannot remove a value from an enum. Create a new type without it, convert each"
                                    + " column with ALTER COLUMN ... TYPE ... USING, and drop the old type.");
            case Change.EnumValuesReordered c ->
                    leaveOut("PostgreSQL cannot reorder enum values. The type would have to be created again.");
            case Change.EnumValueRenameCandidate c -> {
                // Not confirmed, so it stays a removal and an addition.
            }
            case Change.TableAdded c -> createTable(c.table());
            case Change.TableDropped c -> {
                // Planned together, in an order that respects foreign keys, see dropTables().
            }
            case Change.TablePersistenceChanged c -> {
                if (c.before() == Persistence.TEMPORARY || c.after() == Persistence.TEMPORARY) {
                    leaveOut("A table cannot be changed to or from temporary. Create it again and copy the rows.");
                } else {
                    alter(Stage.TABLE_OPTIONS, c.table(), c.after() == Persistence.UNLOGGED ? "SET UNLOGGED" : "SET LOGGED", Operation.SET_UNLOGGED);
                }
            }
            case Change.TablePartitioningChanged c ->
                    leaveOut("PostgreSQL cannot change how an existing table is partitioned. Create it again and copy the rows.");
            case Change.TableInheritanceChanged c -> {
                c.before().stream()
                        .filter(parent -> !c.after().contains(parent))
                        .forEach(parent -> alter(Stage.TABLE_OPTIONS, c.table(), "NO INHERIT " + parent.toSql(), null));
                c.after().stream()
                        .filter(parent -> !c.before().contains(parent))
                        .forEach(parent -> alter(Stage.TABLE_OPTIONS, c.table(), "INHERIT " + parent.toSql(), null));
            }
            case Change.TableRenameCandidate c -> {
                // Not confirmed, so it stays a drop and a create.
            }
            case Change.ColumnAdded c ->
                    alter(Stage.ADD_COLUMNS, c.table(), "ADD COLUMN " + SqlWriter.column(c.column()), direct(c));
            case Change.ColumnDropped c ->
                    alter(Stage.DROP_COLUMNS, c.table(), "DROP COLUMN " + SqlWriter.name(c.column().name()), Operation.DROP_COLUMN);
            case Change.ColumnTypeChanged c -> retype(c.table(), c.column());
            case Change.ColumnCollationChanged c -> retype(c.table(), c.column());
            case Change.ColumnNullabilityChanged c -> {
                if (c.notNull()) {
                    setNotNull(c);
                } else {
                    alter(Stage.NULLABLE, c.table(), column(c.column()) + " DROP NOT NULL", Operation.DROP_NOT_NULL);
                }
            }
            case Change.ColumnDefaultChanged c -> {
                if (c.after().isPresent()) {
                    setDefault(c.table(), c.column(), c.after().get());
                } else {
                    alter(Stage.RELEASE_COLUMNS, c.table(), column(c.column()) + " DROP DEFAULT", Operation.DROP_DEFAULT);
                }
            }
            case Change.ColumnIdentityChanged c -> {
                if (c.after() == Identity.NONE) {
                    // Early: the hidden sequence of the identity may have the name a new sequence needs.
                    alter(Stage.DROP_IDENTITY, c.table(), column(c.column()) + " DROP IDENTITY", Operation.DROP_IDENTITY);
                } else if (c.before() == Identity.NONE) {
                    alter(Stage.IDENTITY, c.table(), column(c.column()) + " ADD GENERATED " + word(c.after()) + " AS IDENTITY", Operation.ADD_IDENTITY);
                } else {
                    alter(Stage.IDENTITY, c.table(), column(c.column()) + " SET GENERATED " + word(c.after()), Operation.SET_IDENTITY);
                }
            }
            case Change.ColumnGeneratedChanged c -> {
                if (c.after().isEmpty()) {
                    alter(Stage.RELEASE_COLUMNS, c.table(), column(c.column()) + " DROP EXPRESSION", Operation.DROP_EXPRESSION);
                } else {
                    leaveOut(
                            "PostgreSQL 16 cannot give a column a new generation expression. Drop the column and add it"
                                    + " again, or add a second column and switch over.");
                }
            }
            case Change.ColumnOrderChanged c -> {
                // PostgreSQL cannot move a column. Rule SG-R210 says so; the plan ignores the order.
            }
            case Change.ColumnRenameCandidate c -> {
                // Not confirmed, so it stays a drop and an add.
            }
            case Change.ConstraintAdded c -> addConstraint(c.table(), c.constraint());
            case Change.ConstraintDropped c -> dropConstraint(c.table(), c.constraint());
            case Change.ConstraintRenamed c ->
                    alter(
                            Stage.RENAMES,
                            c.table(),
                            "RENAME CONSTRAINT " + SqlWriter.name(c.from()) + " TO " + SqlWriter.name(c.to()),
                            Operation.RENAME_CONSTRAINT);
            case Change.ConstraintChanged c -> replaceConstraint(c);
            case Change.ConstraintValidated c -> validate(c.table(), c.constraint(), direct(c));
            case Change.IndexAdded c -> createIndex(c.index(), Stage.INDEXES);
            case Change.IndexDropped c -> {
                if (context.kept(c.index().table())) {
                    dropIndex(c.index(), Stage.DROP_INDEXES);
                }
            }
            case Change.IndexRenamed c ->
                    add(
                            Stage.RENAMES,
                            "ALTER INDEX " + new QualifiedName(c.table().schema(), c.from()).toSql() + " RENAME TO " + SqlWriter.name(c.to()),
                            Operation.RENAME_INDEX,
                            c.table());
            case Change.IndexChanged c -> replaceIndex(c);
            case Change.SequenceAdded c -> {
                add(Stage.SEQUENCES, SqlWriter.createSequence(c.sequence()), null, null);
                c.sequence().ownedBy().ifPresent(owner -> ownSequence(c.sequence().name(), owner));
            }
            case Change.SequenceDropped c -> {
                if (!goesWithItsOwner(c.sequence())) {
                    add(Stage.DROP_SEQUENCES, "DROP SEQUENCE " + c.sequence().name().toSql(), null, null);
                }
            }
            case Change.SequenceChanged c -> alterSequence(c);
        }
    }

    // ---------------------------------------------------------------- types

    /**
     * New enum values, last one first. Each is placed before the value that follows it in the
     * new type; working backwards guarantees that this value already exists.
     */
    private void addEnumValues() {
        Map<QualifiedName, List<Change.EnumValueAdded>> byType = new LinkedHashMap<>();
        for (Change change : diff.changes()) {
            if (change instanceof Change.EnumValueAdded added) {
                byType.computeIfAbsent(added.type(), type -> new ArrayList<>()).add(added);
            }
        }
        byType.forEach(
                (name, added) -> {
                    EnumType type = diff.after().enums().get(name);
                    List<String> labels = type.labels();
                    for (int i = labels.size() - 1; i >= 0; i--) {
                        String label = labels.get(i);
                        Optional<Change.EnumValueAdded> change = added.stream().filter(a -> a.value().equals(label)).findFirst();
                        if (change.isPresent()) {
                            current = change.get();
                            String place = i + 1 < labels.size() ? " BEFORE " + SqlWriter.literal(labels.get(i + 1)) : "";
                            add(
                                    Stage.TYPES,
                                    "ALTER TYPE " + name.toSql() + " ADD VALUE " + SqlWriter.literal(label) + place,
                                    Operation.ADD_ENUM_VALUE,
                                    null,
                                    false,
                                    "A new enum value cannot be used before it is committed");
                        }
                    }
                });
        current = null;
    }

    // ---------------------------------------------------------------- tables

    private void createTable(Table table) {
        List<Constraint> inline = table.constraints().stream().filter(c -> !(c instanceof ForeignKey)).toList();
        add(Stage.CREATE_TABLES, SqlWriter.createTable(table, inline), null, null);
        table.constraints().stream()
                .filter(ForeignKey.class::isInstance)
                .forEach(key -> add(Stage.FOREIGN_KEYS, SqlWriter.addConstraint(table, key), Operation.CREATE_TABLE_WITH_FOREIGN_KEY, null));
    }

    /**
     * Dropped tables, each after the tables that point to it with a foreign key. Tables that
     * point to each other in a circle are dropped in one statement, which PostgreSQL allows.
     */
    private void dropTables() {
        List<Change.TableDropped> waiting = new ArrayList<>(context.changes(Change.TableDropped.class).toList());
        while (!waiting.isEmpty()) {
            Set<QualifiedName> names = waiting.stream().map(c -> c.table().name()).collect(Collectors.toSet());
            Optional<Change.TableDropped> free =
                    waiting.stream()
                            .filter(c -> waiting.stream().noneMatch(other -> other != c && references(other.table(), c.table().name())))
                            .findFirst();
            if (free.isPresent()) {
                current = free.get();
                add(Stage.DROP_TABLES, "DROP TABLE " + free.get().table().name().toSql(), Operation.DROP_TABLE, free.get().table().name());
                waiting.remove(free.get());
            } else {
                current = waiting.get(0);
                add(
                        Stage.DROP_TABLES,
                        "DROP TABLE " + names.stream().sorted().map(QualifiedName::toSql).collect(Collectors.joining(", ")),
                        Operation.DROP_TABLE,
                        waiting.get(0).table().name(),
                        true,
                        "These tables refer to each other and have to go together");
                waiting.clear();
            }
        }
        current = null;
    }

    private static boolean references(Table table, QualifiedName target) {
        return table.constraints().stream().anyMatch(c -> c instanceof ForeignKey key && key.referencedTable().equals(target));
    }

    // ---------------------------------------------------------------- columns

    /**
     * One {@code ALTER COLUMN ... TYPE} for a column whose type, collation or both change.
     * The collation is always written: without it PostgreSQL resets the column to the default
     * collation of the type.
     */
    private void retype(QualifiedName table, String name) {
        Column before = context.tableBefore(table).flatMap(t -> t.column(name)).orElseThrow();
        Column after = context.tableAfter(table).flatMap(t -> t.column(name)).orElseThrow();
        if (!retyped.add(new ColumnRef(table, name))) {
            return;
        }
        TypeChange change = new TypeChange(before.type(), after.type());
        boolean converts = change.kind() == TypeChange.Kind.CONVERSION;
        String sql =
                column(name) + " TYPE " + after.type().toSql()
                        + after.collation().map(collation -> " COLLATE " + collation).orElse("")
                        + (converts ? " USING " + SqlWriter.name(name) + "::" + after.type().toSql() : "");
        boolean typeChanged = !before.type().equals(after.type());
        Operation operation =
                !typeChanged
                        ? (context.indexed(table, name) ? Operation.ALTER_COLLATION_INDEXED : Operation.ALTER_COLLATION)
                        : (change.rewrites() ? Operation.ALTER_TYPE_REWRITE : Operation.ALTER_TYPE_NO_REWRITE);
        if (converts && before.defaultValue().isPresent()) {
            // A default of the old type cannot be cast along with the column: take it off first.
            boolean defaultStays = before.defaultValue().equals(after.defaultValue());
            alter(Stage.RELEASE_COLUMNS, table, column(name) + " DROP DEFAULT", Operation.DROP_DEFAULT);
            if (defaultStays) {
                setDefault(table, name, after.defaultValue().get());
            }
        }
        alter(Stage.COLUMN_TYPES, table, sql, operation);
    }

    private void setDefault(QualifiedName table, String name, Expression value) {
        alter(Stage.DEFAULTS, table, column(name) + " SET DEFAULT " + SqlWriter.defaultExpression(value), Operation.SET_DEFAULT);
    }

    /**
     * On the safe route, where the table can have rows, NOT NULL is reached through a CHECK
     * constraint: adding it unvalidated is instant, validating it does not block, and with
     * the valid constraint as proof {@code SET NOT NULL} needs no scan of its own.
     */
    private void setNotNull(Change.ColumnNullabilityChanged c) {
        QualifiedName table = c.table();
        boolean needsProof = safe(table) && !context.provenNotNull(table, c.column());
        if (!needsProof) {
            alter(Stage.NOT_NULL, table, column(c.column()) + " SET NOT NULL", direct(c));
            return;
        }
        String proof = freeName(table.name() + "_" + c.column(), "_not_null");
        String name = SqlWriter.name(proof);
        alter(
                Stage.NOT_NULL,
                table,
                "ADD CONSTRAINT " + name + " CHECK (" + SqlWriter.name(c.column()) + " IS NOT NULL) NOT VALID",
                Operation.ADD_CHECK_NOT_VALID,
                "Proof for SET NOT NULL: added without checking the existing rows");
        alter(Stage.NOT_NULL, table, "VALIDATE CONSTRAINT " + name, Operation.VALIDATE_CHECK, "Checks the rows without blocking reads or writes");
        alter(
                Stage.NOT_NULL,
                table,
                column(c.column()) + " SET NOT NULL",
                Operation.SET_NOT_NULL_WITH_VALID_CHECK,
                "No scan: the valid constraint proves there is no NULL");
        alter(Stage.NOT_NULL, table, "DROP CONSTRAINT " + name, Operation.DROP_CONSTRAINT, "The proof is no longer needed");
    }

    // ---------------------------------------------------------------- constraints

    private void addConstraint(QualifiedName tableName, Constraint constraint) {
        Table table = context.tableAfter(tableName).orElseThrow();
        boolean safe = safe(tableName);
        switch (constraint) {
            case Check check when safe && !check.notValid() -> {
                add(Stage.KEYS, SqlWriter.addConstraint(table, check.withNotValid(true)), Operation.ADD_CHECK_NOT_VALID, tableName);
                validate(tableName, check.name().orElseThrow(), Operation.VALIDATE_CHECK);
            }
            case ForeignKey key when safe && !key.notValid() -> {
                add(Stage.FOREIGN_KEYS, SqlWriter.addConstraint(table, key.withNotValid(true)), Operation.ADD_FOREIGN_KEY_NOT_VALID, tableName);
                validate(tableName, key.name().orElseThrow(), Operation.VALIDATE_FOREIGN_KEY);
            }
            case PrimaryKey key when safe -> addKeyThroughIndex(tableName, key, key.name().orElseThrow(), "");
            case Unique key when safe -> addKeyThroughIndex(tableName, key, key.name().orElseThrow(), "");
            case ForeignKey key -> add(Stage.FOREIGN_KEYS, SqlWriter.addConstraint(table, key), addOperation(key), tableName);
            default -> add(Stage.KEYS, SqlWriter.addConstraint(table, constraint), addOperation(constraint), tableName);
        }
    }

    /**
     * A primary key or unique constraint without the long lock: the unique index is built
     * concurrently, then the constraint takes the finished index over.
     *
     * @param index       the name to build the index under
     * @param dropFirst   an action to run in the same statement before the key is added, with
     *                    its trailing comma, or the empty string
     */
    private void addKeyThroughIndex(QualifiedName table, Constraint key, String index, String dropFirst) {
        boolean primary = key instanceof PrimaryKey;
        List<String> columns = primary ? ((PrimaryKey) key).columns() : ((Unique) key).columns();
        List<String> include = primary ? ((PrimaryKey) key).include() : ((Unique) key).include();
        boolean nullsNotDistinct = !primary && ((Unique) key).nullsNotDistinct();
        Index model =
                new Index(
                        Optional.of(index),
                        table,
                        true,
                        "btree",
                        columns.stream().map(IndexColumn::ofColumn).toList(),
                        include,
                        nullsNotDistinct,
                        Optional.empty());
        add(Stage.KEYS, concurrently(SqlWriter.createIndex(model)), Operation.CREATE_INDEX_CONCURRENTLY, table, false, "Builds the index of the key without blocking writes");
        alter(
                Stage.KEYS,
                table,
                dropFirst + "ADD " + SqlWriter.constraintUsingIndex(key, index),
                primary ? Operation.ADD_PRIMARY_KEY_USING_INDEX : Operation.ADD_CONSTRAINT_USING_INDEX,
                "The constraint takes over the finished index");
    }

    private void dropConstraint(QualifiedName table, Constraint constraint) {
        boolean foreign = constraint instanceof ForeignKey;
        alter(
                foreign ? Stage.DROP_FOREIGN_KEYS : Stage.DROP_CONSTRAINTS,
                table,
                "DROP CONSTRAINT " + SqlWriter.name(constraint.name().orElseThrow()),
                foreign ? Operation.DROP_FOREIGN_KEY : Operation.DROP_CONSTRAINT);
    }

    /**
     * PostgreSQL cannot alter a constraint's definition. The direct route drops the old one
     * and adds the new one. The safe route adds the new definition under a temporary name
     * first, so the table is never without the rule, and swaps the names at the end.
     */
    private void replaceConstraint(Change.ConstraintChanged c) {
        QualifiedName tableName = c.table();
        Table table = context.tableAfter(tableName).orElseThrow();
        Constraint after = c.after();
        String name = after.name().orElseThrow();
        boolean unvalidated = (after instanceof Check check && check.notValid()) || (after instanceof ForeignKey key && key.notValid());
        if (!safe(tableName) || unvalidated) {
            dropConstraint(tableName, c.before());
            add(after instanceof ForeignKey ? Stage.FOREIGN_KEYS : Stage.KEYS, SqlWriter.addConstraint(table, after), addOperation(after), tableName);
            return;
        }
        String temporary = freeName(name, "_new");
        switch (after) {
            case Check check -> {
                add(Stage.KEYS, SqlWriter.addConstraint(table, check.withName(Optional.of(temporary)).withNotValid(true)), Operation.ADD_CHECK_NOT_VALID, tableName, true, "The new definition, next to the old one");
                validate(tableName, temporary, Operation.VALIDATE_CHECK);
                swapConstraint(tableName, name, temporary);
            }
            case ForeignKey key -> {
                add(Stage.FOREIGN_KEYS, SqlWriter.addConstraint(table, key.withName(Optional.of(temporary)).withNotValid(true)), Operation.ADD_FOREIGN_KEY_NOT_VALID, tableName, true, "The new definition, next to the old one");
                validate(tableName, temporary, Operation.VALIDATE_FOREIGN_KEY);
                swapConstraint(tableName, name, temporary);
            }
            case PrimaryKey key -> addKeyThroughIndex(tableName, key, temporary, "DROP CONSTRAINT " + SqlWriter.name(name) + ", ");
            case Unique key -> addKeyThroughIndex(tableName, key, temporary, "DROP CONSTRAINT " + SqlWriter.name(name) + ", ");
        }
    }

    private void swapConstraint(QualifiedName table, String name, String temporary) {
        alter(Stage.SWAP, table, "DROP CONSTRAINT " + SqlWriter.name(name), Operation.DROP_CONSTRAINT, "The new definition is valid; the old one can go");
        alter(Stage.SWAP, table, "RENAME CONSTRAINT " + SqlWriter.name(temporary) + " TO " + SqlWriter.name(name), Operation.RENAME_CONSTRAINT);
    }

    private void validate(QualifiedName table, String constraint, Operation operation) {
        alter(Stage.VALIDATE, table, "VALIDATE CONSTRAINT " + SqlWriter.name(constraint), operation);
    }

    private static Operation addOperation(Constraint constraint) {
        return switch (constraint) {
            case Check check -> check.notValid() ? Operation.ADD_CHECK_NOT_VALID : Operation.ADD_CHECK;
            case ForeignKey key -> key.notValid() ? Operation.ADD_FOREIGN_KEY_NOT_VALID : Operation.ADD_FOREIGN_KEY;
            case PrimaryKey key -> Operation.ADD_PRIMARY_KEY;
            case Unique key -> Operation.ADD_UNIQUE;
        };
    }

    // ---------------------------------------------------------------- indexes

    private void createIndex(Index index, Stage stage) {
        if (safe(index.table())) {
            add(stage, concurrently(SqlWriter.createIndex(index)), Operation.CREATE_INDEX_CONCURRENTLY, index.table(), false, "");
        } else {
            add(stage, SqlWriter.createIndex(index), Operation.CREATE_INDEX, index.table());
        }
    }

    private void dropIndex(Index index, Stage stage) {
        String name = new QualifiedName(index.table().schema(), index.name().orElseThrow()).toSql();
        if (safe(index.table())) {
            add(stage, "DROP INDEX CONCURRENTLY " + name, Operation.DROP_INDEX_CONCURRENTLY, index.table(), false, "");
        } else {
            add(stage, "DROP INDEX " + name, Operation.DROP_INDEX, index.table());
        }
    }

    /** As for constraints: the safe route builds the new index first and swaps the names. */
    private void replaceIndex(Change.IndexChanged c) {
        Index after = c.after();
        if (!safe(after.table())) {
            dropIndex(c.before(), Stage.DROP_INDEXES);
            createIndex(after, Stage.INDEXES);
            return;
        }
        String name = after.name().orElseThrow();
        String temporary = freeName(name, "_new");
        createIndex(after.withName(Optional.of(temporary)), Stage.INDEXES);
        dropIndex(c.before(), Stage.SWAP);
        add(
                Stage.SWAP,
                "ALTER INDEX " + new QualifiedName(after.table().schema(), temporary).toSql() + " RENAME TO " + SqlWriter.name(name),
                Operation.RENAME_INDEX,
                after.table());
    }

    private static String concurrently(String createIndex) {
        return createIndex.replaceFirst("INDEX ", "INDEX CONCURRENTLY ");
    }

    // ---------------------------------------------------------------- sequences

    private void ownSequence(QualifiedName sequence, ColumnRef owner) {
        add(Stage.SEQUENCE_OPTIONS, "ALTER SEQUENCE " + sequence.toSql() + " OWNED BY " + SqlWriter.ownedBy(owner), null, null);
    }

    private void alterSequence(Change.SequenceChanged c) {
        Sequence after = c.after();
        boolean ownerChanged = !c.before().ownedBy().equals(after.ownedBy());
        if (ownerChanged && goesWithItsOwner(c.before())) {
            // PostgreSQL drops a sequence together with the column that owns it.
            add(
                    Stage.SEQUENCES,
                    "ALTER SEQUENCE " + after.name().toSql() + " OWNED BY NONE",
                    null,
                    null,
                    true,
                    "Sets the sequence free, so it is not dropped with the column that owns it now");
        }
        if (!c.before().withOwnedBy(after.ownedBy()).equals(after)) {
            add(Stage.SEQUENCE_OPTIONS, "ALTER SEQUENCE " + after.name().toSql() + SqlWriter.sequenceOptions(after), null, null);
        }
        if (ownerChanged) {
            add(
                    Stage.SEQUENCE_OPTIONS,
                    "ALTER SEQUENCE " + after.name().toSql() + " OWNED BY " + after.ownedBy().map(SqlWriter::ownedBy).orElse("NONE"),
                    null,
                    null);
        }
    }

    /** True if PostgreSQL drops the sequence by itself, because the column that owns it goes away. */
    private boolean goesWithItsOwner(Sequence sequence) {
        return sequence.ownedBy()
                .filter(owner -> context.tableAfter(owner.table()).flatMap(table -> table.column(owner.column())).isEmpty())
                .isPresent();
    }

    // ---------------------------------------------------------------- helpers

    /** True if the non-blocking variant of a statement on this table is worth its extra steps. */
    private boolean safe(QualifiedName table) {
        return options.route() == Route.SAFE && context.mayHoldRows(table);
    }

    private Operation direct(Change change) {
        return DirectRoute.of(change, context).orElse(null);
    }

    private void leaveOut(String reason) {
        unplanned.add(new MigrationPlan.Unplanned(current, reason));
    }

    private void alter(Stage stage, QualifiedName table, String action, Operation operation) {
        alter(stage, table, action, operation, "");
    }

    private void alter(Stage stage, QualifiedName table, String action, Operation operation, String note) {
        add(stage, "ALTER TABLE " + table.toSql() + " " + action, operation, table, true, note);
    }

    private void add(Stage stage, String sql, Operation operation, QualifiedName table) {
        add(stage, sql, operation, table, true, "");
    }

    private void add(Stage stage, String sql, Operation operation, QualifiedName table, boolean transactional, String note) {
        stages.computeIfAbsent(stage, s -> new ArrayList<>())
                .add(new Step(sql, Optional.ofNullable(operation), Optional.ofNullable(table), transactional, note, List.of(), Optional.ofNullable(current)));
    }

    private static String column(String name) {
        return "ALTER COLUMN " + SqlWriter.name(name);
    }

    private static String word(Identity identity) {
        return identity == Identity.ALWAYS ? "ALWAYS" : "BY DEFAULT";
    }

    /** Orders statements by the work they do on the rows, then by the strength of their lock. */
    private static int weight(Step step) {
        return step.operation()
                .map(operation -> operation.work().ordinal() * 10 + operation.lock().map(lock -> lock.ordinal() + 1).orElse(0))
                .orElse(0);
    }

    /** A name for a temporary constraint or index: within 63 characters and not taken. */
    private String freeName(String base, String suffix) {
        String stem = base.length() + suffix.length() > 60 ? base.substring(0, 60 - suffix.length()) : base;
        String name = stem + suffix;
        for (int i = 2; !usedNames.add(name); i++) {
            name = stem + suffix + i;
        }
        return name;
    }

    /**
     * Marks the steps whose risk the safe route could not remove: a table rewrite, a drop.
     * Of the steps of such a change, the one that does the most work under the strongest lock
     * carries the titles of the rules that still apply.
     */
    private List<Step> withCautions(List<Step> steps) {
        Map<Change, List<String>> open = new LinkedHashMap<>();
        for (Finding finding : RiskAnalyzer.analyze(diff).findings()) {
            boolean serious = finding.severity().compareTo(RiskLevel.MEDIUM) >= 0;
            if (serious && !SOLVED_BY_SAFE_ROUTE.contains(finding.rule().id())) {
                open.computeIfAbsent(finding.change(), change -> new ArrayList<>())
                        .add(finding.rule().id() + " " + finding.rule().title());
            }
        }
        Map<Change, Step> heaviest = new LinkedHashMap<>();
        for (Step step : steps) {
            step.change().filter(open::containsKey).ifPresent(change -> heaviest.merge(change, step, (a, b) -> weight(b) > weight(a) ? b : a));
        }
        List<Step> marked = new ArrayList<>();
        for (Step step : steps) {
            boolean carries = step.change().filter(change -> heaviest.get(change) == step).isPresent();
            marked.add(carries ? step.withCautions(open.get(step.change().get())) : step);
        }
        return marked;
    }
}
