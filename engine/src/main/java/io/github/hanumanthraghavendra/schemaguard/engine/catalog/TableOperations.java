package io.github.hanumanthraghavendra.schemaguard.engine.catalog;

import io.github.hanumanthraghavendra.schemaguard.engine.lexer.LexResult;
import io.github.hanumanthraghavendra.schemaguard.engine.lexer.Lexer;
import io.github.hanumanthraghavendra.schemaguard.engine.lexer.Token;
import io.github.hanumanthraghavendra.schemaguard.engine.lexer.TokenType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Check;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Column;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ColumnRef;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Constraint;
import io.github.hanumanthraghavendra.schemaguard.engine.model.DataType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Expression;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ForeignKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.GeneratedColumn;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Identifiers;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Identity;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Index;
import io.github.hanumanthraghavendra.schemaguard.engine.model.IndexColumn;
import io.github.hanumanthraghavendra.schemaguard.engine.model.PrimaryKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Sequence;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Table;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Unique;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.AlterAction;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.Expressions;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserOptions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Everything that creates or changes a table, following PostgreSQL's rules: what a new column
 * or constraint needs, what a rename has to update, and what a drop takes with it.
 */
final class TableOperations {

    private static final Map<String, String> SERIAL_TYPES =
            Map.of("serial", "integer", "bigserial", "bigint", "smallserial", "smallint");

    private final CatalogState state;
    private final ParserOptions options;

    TableOperations(CatalogState state, ParserOptions options) {
        this.state = state;
        this.options = options;
    }

    // ---------------------------------------------------------------- CREATE TABLE

    void create(Table parsed) {
        QualifiedName name = parsed.name();
        state.tables.put(
                name,
                new Table(
                        name, List.of(), List.of(), parsed.persistence(), parsed.inherits(),
                        parsed.partitionBy()));
        for (Column column : parsed.columns()) {
            addColumn(name, column);
        }
        for (Constraint constraint : withoutRedundantKeys(parsed.constraints())) {
            addConstraint(name, constraint);
        }
    }

    /**
     * Orders and merges key constraints as PostgreSQL does within one CREATE TABLE: the primary
     * key comes first, and a unique constraint that repeats an earlier key (same columns and
     * settings) is not created again. {@code id int PRIMARY KEY, UNIQUE (id)} yields one key.
     * If the earlier key had no name and the repeat has one, the earlier key takes that name.
     */
    private static List<Constraint> withoutRedundantKeys(List<Constraint> constraints) {
        List<Constraint> ordered = new ArrayList<>();
        constraints.stream().filter(PrimaryKey.class::isInstance).forEach(ordered::add);
        constraints.stream().filter(c -> !(c instanceof PrimaryKey)).forEach(ordered::add);

        List<Constraint> kept = new ArrayList<>();
        for (Constraint constraint : ordered) {
            int repeats = -1;
            for (int i = 0; i < kept.size() && repeats < 0; i++) {
                boolean sameKey = constraint instanceof Unique && keyShape(kept.get(i)).equals(keyShape(constraint));
                repeats = sameKey ? i : -1;
            }
            if (repeats < 0) {
                kept.add(constraint);
            } else if (kept.get(repeats).name().isEmpty()) {
                kept.set(repeats, kept.get(repeats).withName(constraint.name()));
            }
        }
        return kept;
    }

    /** Everything that makes two key constraints the same index, or empty for a non-key constraint. */
    private static Optional<List<Object>> keyShape(Constraint constraint) {
        return switch (constraint) {
            case PrimaryKey pk -> Optional.of(List.of(pk.columns(), pk.include(), false, pk.deferrability()));
            case Unique unique ->
                    Optional.of(
                            List.of(
                                    unique.columns(), unique.include(), unique.nullsNotDistinct(),
                                    unique.deferrability()));
            case ForeignKey fk -> Optional.empty();
            case Check check -> Optional.empty();
        };
    }

    // ---------------------------------------------------------------- ALTER TABLE

    /** Applies one action and returns the table's name afterwards, which a rename changes. */
    QualifiedName apply(QualifiedName table, AlterAction action) {
        switch (action) {
            case AlterAction.AddColumn add -> {
                if (state.table(table).column(add.column().name()).isPresent()) {
                    if (add.ifNotExists()) {
                        return table;
                    }
                    throw CatalogError.duplicateColumn(columnOf(add.column().name(), table) + " already exists");
                }
                addColumn(table, add.column());
                for (Constraint constraint : add.constraints()) {
                    addConstraint(table, constraint);
                }
            }
            case AlterAction.DropColumn drop -> dropColumn(table, drop.column(), drop.ifExists(), drop.cascade());
            case AlterAction.AlterColumnType change -> {
                Column column = column(table, change.column());
                replaceColumn(table, normalise(column.withType(change.type()).withCollation(change.collation())));
            }
            case AlterAction.SetDefault set ->
                    replaceColumn(
                            table, normalise(column(table, set.column()).withDefaultValue(Optional.of(set.value()))));
            case AlterAction.DropDefault drop ->
                    replaceColumn(table, column(table, drop.column()).withDefaultValue(Optional.empty()));
            case AlterAction.SetNotNull set -> replaceColumn(table, column(table, set.column()).withNotNull(true));
            case AlterAction.DropNotNull drop -> dropNotNull(table, drop.column());
            case AlterAction.AddIdentity add -> addIdentity(table, add);
            case AlterAction.DropIdentity drop -> {
                Column column = column(table, drop.column());
                if (column.identity() != Identity.NONE) {
                    replaceColumn(table, column.withIdentity(Identity.NONE));
                } else if (!drop.ifExists()) {
                    throw CatalogError.invalid(columnOf(drop.column(), table) + " is not an identity column");
                }
            }
            case AlterAction.AddConstraint add -> addConstraint(table, add.constraint());
            case AlterAction.AddConstraintUsingIndex add -> promoteIndex(table, add);
            case AlterAction.DropConstraint drop -> dropConstraint(table, drop.name(), drop.ifExists(), drop.cascade());
            case AlterAction.ValidateConstraint validate -> validateConstraint(table, validate.name());
            case AlterAction.RenameColumn rename -> renameColumn(table, rename.from(), rename.to());
            case AlterAction.RenameConstraint rename -> renameConstraint(table, rename.from(), rename.to());
            case AlterAction.RenameTable rename -> {
                return moveTable(table, new QualifiedName(table.schema(), rename.to()));
            }
            case AlterAction.SetSchema set -> {
                return moveTable(table, new QualifiedName(set.schema(), table.name()));
            }
        }
        return table;
    }

    // ---------------------------------------------------------------- columns

    private void addColumn(QualifiedName table, Column column) {
        Table current = state.table(table);
        Column prepared = expandSerial(current, normalise(column));
        state.tables.put(table, current.withColumns(append(current.columns(), prepared)));
    }

    /**
     * Brings a column to the form the server would store: a type that names a known enum is
     * qualified, a default that calls nextval on a known sequence names it in full, and the
     * collation is reduced to its canonical spelling.
     */
    private Column normalise(Column column) {
        DataType type = column.type();
        boolean knownEnum =
                type.schema() == null
                        && state.enums.containsKey(new QualifiedName(options.defaultSchema(), type.name()));
        if (knownEnum) {
            type = new DataType(options.defaultSchema(), type.name(), type.modifiers(), type.arrayDimensions());
        }
        Optional<Expression> defaultValue =
                column.defaultValue()
                        .map(
                                d ->
                                        sequenceOf(d)
                                                .filter(state.sequences::containsKey)
                                                .map(TableOperations::nextval)
                                                .orElse(d));
        return column.withType(type)
                .withDefaultValue(defaultValue)
                .withCollation(column.collation().flatMap(TableOperations::normaliseCollation));
    }

    /** {@code pg_dump} qualifies built-in collations and omits the default one. */
    static Optional<String> normaliseCollation(String collation) {
        String prefix = "pg_catalog.";
        String name = collation.startsWith(prefix) ? collation.substring(prefix.length()) : collation;
        return name.equals("\"default\"") ? Optional.empty() : Optional.of(name);
    }

    /**
     * {@code serial} is not a type but shorthand: an integer column that is NOT NULL and takes
     * its default from a sequence created with it and owned by it.
     */
    private Column expandSerial(Table table, Column column) {
        DataType type = column.type();
        String base = type.schema() == null && type.arrayDimensions() == 0 ? SERIAL_TYPES.get(type.name()) : null;
        if (base == null) {
            return column;
        }
        String schema = table.name().schema();
        String sequenceName =
                NameGenerator.unique(
                        table.name().name(),
                        column.name(),
                        "seq",
                        n -> state.relationExists(new QualifiedName(schema, n)));
        QualifiedName sequence = new QualifiedName(schema, sequenceName);
        state.sequences.put(
                sequence,
                SequenceDefaults.resolve(
                        new Sequence(
                                sequence,
                                table.persistence(),
                                Optional.of(DataType.of(base)),
                                Optional.empty(),
                                1,
                                Optional.empty(),
                                Optional.empty(),
                                1,
                                false,
                                Optional.of(new ColumnRef(table.name(), column.name())))));
        return column.withType(DataType.of(base)).withNotNull(true).withDefaultValue(Optional.of(nextval(sequence)));
    }

    static Expression nextval(QualifiedName sequence) {
        return new Expression("nextval('" + sequence.toSql().replace("'", "''") + "'::regclass)");
    }

    /** True if the default draws from the given sequence, however the call was written. */
    boolean usesSequence(Expression defaultValue, QualifiedName sequence) {
        return sequenceOf(defaultValue).equals(Optional.of(sequence));
    }

    /**
     * The sequence a default draws from, if the default is exactly {@code nextval('name')},
     * with or without a schema and a {@code ::regclass} cast.
     */
    private Optional<QualifiedName> sequenceOf(Expression defaultValue) {
        String fingerprint = Expressions.fingerprint(defaultValue);
        String prefix = "nextval '";
        if (!fingerprint.startsWith(prefix) || !fingerprint.endsWith("'") || fingerprint.length() <= prefix.length()) {
            return Optional.empty();
        }
        String written = fingerprint.substring(prefix.length(), fingerprint.length() - 1).replace("''", "'");
        LexResult lexed = Lexer.tokenize(written);
        List<Token> tokens = lexed.tokens();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < tokens.size() - 1; i++) {
            TokenType type = tokens.get(i).type();
            boolean isName = type == TokenType.WORD || type == TokenType.QUOTED_IDENTIFIER;
            boolean wantName = i % 2 == 0;
            if (wantName ? !isName : type != TokenType.DOT) {
                return Optional.empty();
            }
            if (wantName) {
                parts.add(tokens.get(i).value());
            }
        }
        // A well-formed name is name, or name.name, and so on: an even count once EOF is included.
        if (lexed.hasErrors() || parts.isEmpty() || tokens.size() % 2 != 0) {
            return Optional.empty();
        }
        int n = parts.size();
        String schema = n == 1 ? options.defaultSchema() : parts.get(n - 2);
        return Optional.of(new QualifiedName(schema, parts.get(n - 1)));
    }

    private void dropNotNull(QualifiedName table, String name) {
        Column column = column(table, name);
        boolean inPrimaryKey =
                state.table(table).primaryKey().filter(pk -> pk.columns().contains(name)).isPresent();
        if (inPrimaryKey) {
            throw CatalogError.invalid("column \"" + name + "\" is in a primary key");
        }
        if (column.identity() != Identity.NONE) {
            throw CatalogError.invalid(columnOf(name, table) + " is an identity column");
        }
        replaceColumn(table, column.withNotNull(false));
    }

    private void addIdentity(QualifiedName table, AlterAction.AddIdentity add) {
        Column column = column(table, add.column());
        if (column.identity() != Identity.NONE) {
            throw CatalogError.invalid(columnOf(add.column(), table) + " is already an identity column");
        }
        if (!column.notNull()) {
            throw CatalogError.invalid(
                    columnOf(add.column(), table) + " must be declared NOT NULL before identity can be added");
        }
        if (column.defaultValue().isPresent()) {
            throw CatalogError.invalid(columnOf(add.column(), table) + " already has a default value");
        }
        replaceColumn(table, column.withIdentity(add.identity()));
    }

    /**
     * Drops a column and what depends on it. Constraints and indexes of the same table that use
     * the column go with it silently. Things outside the column's own table row, foreign keys
     * that point at it and generated columns computed from it, need CASCADE.
     */
    void dropColumn(QualifiedName table, String name, boolean ifExists, boolean cascade) {
        Table current = state.table(table);
        if (current.column(name).isEmpty()) {
            if (ifExists) {
                return;
            }
            throw CatalogError.undefinedColumn(columnOf(name, table) + " does not exist");
        }
        List<String> computedFromIt =
                current.columns().stream()
                        .filter(c -> !c.name().equals(name))
                        .filter(c -> c.generated().filter(g -> mentions(g.expression(), name)).isPresent())
                        .map(Column::name)
                        .toList();
        BiPredicate<QualifiedName, ForeignKey> pointsAtIt =
                (owner, fk) ->
                        fk.referencedTable().equals(table)
                                && fk.referencedColumns().contains(name)
                                && !(owner.equals(table) && fk.columns().contains(name));
        if (!cascade && (!computedFromIt.isEmpty() || anyForeignKey(pointsAtIt))) {
            throw CatalogError.dependentObjects(
                    "cannot drop column " + name + " of table " + table.name()
                            + " because other objects depend on it");
        }
        removeForeignKeys(pointsAtIt);
        for (String dependent : computedFromIt) {
            dropColumn(table, dependent, true, true);
        }

        current = state.table(table);
        state.tables.put(
                table,
                current.withColumns(current.columns().stream().filter(c -> !c.name().equals(name)).toList())
                        .withConstraints(current.constraints().stream().filter(c -> !involves(c, name)).toList()));
        state.indexes.values().removeIf(index -> index.table().equals(table) && involves(index, name));
        ColumnRef dropped = new ColumnRef(table, name);
        state.sequences.values().removeIf(sequence -> sequence.ownedBy().equals(Optional.of(dropped)));
    }

    private void renameColumn(QualifiedName table, String from, String to) {
        Table current = state.table(table);
        if (current.column(from).isEmpty()) {
            throw CatalogError.undefinedColumn("column \"" + from + "\" does not exist");
        }
        if (current.column(to).isPresent()) {
            throw CatalogError.duplicateColumn(columnOf(to, table) + " already exists");
        }
        UnaryOperator<List<String>> rename = names -> names.stream().map(n -> n.equals(from) ? to : n).toList();

        List<Column> columns =
                current.columns().stream()
                        .map(c -> c.name().equals(from) ? c.withName(to) : c)
                        .map(
                                c ->
                                        c.withGenerated(
                                                c.generated()
                                                        .map(
                                                                g ->
                                                                        new GeneratedColumn(
                                                                                Expressions.renameColumn(
                                                                                        g.expression(), from, to),
                                                                                g.stored()))))
                        .toList();
        List<Constraint> constraints =
                current.constraints().stream()
                        .<Constraint>map(
                                c ->
                                        switch (c) {
                                            case PrimaryKey pk ->
                                                    new PrimaryKey(
                                                            pk.name(),
                                                            rename.apply(pk.columns()),
                                                            rename.apply(pk.include()),
                                                            pk.deferrability());
                                            case Unique unique ->
                                                    new Unique(
                                                            unique.name(),
                                                            rename.apply(unique.columns()),
                                                            rename.apply(unique.include()),
                                                            unique.nullsNotDistinct(),
                                                            unique.deferrability());
                                            case ForeignKey fk ->
                                                    fk.withReferences(
                                                            rename.apply(fk.columns()),
                                                            fk.referencedTable(),
                                                            fk.referencedColumns());
                                            case Check check ->
                                                    check.withExpression(
                                                            Expressions.renameColumn(check.expression(), from, to));
                                        })
                        .toList();
        state.tables.put(table, current.withColumns(columns).withConstraints(constraints));

        // Foreign keys anywhere, this table's own included, that point at the renamed column.
        mapForeignKeys(
                fk ->
                        fk.referencedTable().equals(table)
                                ? fk.withReferences(fk.columns(), table, rename.apply(fk.referencedColumns()))
                                : fk);
        state.indexes.replaceAll(
                (key, index) ->
                        index.table().equals(table)
                                ? new Index(
                                        index.name(),
                                        index.table(),
                                        index.unique(),
                                        index.method(),
                                        index.columns().stream().map(k -> renameIn(k, from, to)).toList(),
                                        rename.apply(index.include()),
                                        index.nullsNotDistinct(),
                                        index.predicate().map(p -> Expressions.renameColumn(p, from, to)))
                                : index);
        ColumnRef old = new ColumnRef(table, from);
        state.sequences.replaceAll(
                (key, sequence) ->
                        sequence.ownedBy().equals(Optional.of(old))
                                ? sequence.withOwnedBy(Optional.of(new ColumnRef(table, to)))
                                : sequence);
    }

    private static IndexColumn renameIn(IndexColumn key, String from, String to) {
        return new IndexColumn(
                key.column().map(c -> c.equals(from) ? to : c),
                key.expression().map(e -> Expressions.renameColumn(e, from, to)),
                key.collation(),
                key.operatorClass(),
                key.order(),
                key.nulls());
    }

    // ---------------------------------------------------------------- constraints

    void addConstraint(QualifiedName table, Constraint constraint) {
        Table current = state.table(table);
        Constraint prepared =
                switch (constraint) {
                    case PrimaryKey pk -> {
                        if (current.primaryKey().isPresent()) {
                            throw CatalogError.invalid(
                                    "multiple primary keys for table \"" + table.name() + "\" are not allowed");
                        }
                        // The key columns are made NOT NULL first, so a missing one is reported that way.
                        pk.columns().forEach(c -> column(table, c));
                        requireKeyColumns(current, pk.include());
                        yield pk;
                    }
                    case Unique unique -> {
                        requireKeyColumns(current, unique.columns());
                        requireKeyColumns(current, unique.include());
                        yield unique;
                    }
                    case ForeignKey fk -> resolveForeignKey(current, fk);
                    case Check check -> check;
                };

        boolean ownsIndex = CatalogState.isKey(prepared);
        Predicate<String> taken =
                n ->
                        current.constraint(n).isPresent()
                                || (ownsIndex && state.relationExists(new QualifiedName(table.schema(), n)));
        String name;
        if (prepared.name().isEmpty()) {
            name = generateName(current, prepared, taken);
        } else {
            name = prepared.name().get();
            if (current.constraint(name).isPresent()) {
                throw CatalogError.duplicateObject(
                        "constraint \"" + name + "\" for relation \"" + table.name() + "\" already exists");
            }
            if (taken.test(name)) {
                throw CatalogError.duplicateObject("relation \"" + name + "\" already exists");
            }
        }

        List<Column> columns = current.columns();
        if (prepared instanceof PrimaryKey pk) {
            // A primary key makes its columns NOT NULL.
            columns = columns.stream().map(c -> pk.columns().contains(c.name()) ? c.withNotNull(true) : c).toList();
        }
        state.tables.put(
                table,
                current.withColumns(columns)
                        .withConstraints(append(current.constraints(), prepared.withName(Optional.of(name)))));
    }

    private static String generateName(Table table, Constraint constraint, Predicate<String> taken) {
        String relation = table.name().name();
        return switch (constraint) {
            case PrimaryKey pk -> NameGenerator.unique(relation, null, "pkey", taken);
            case Unique unique ->
                    NameGenerator.unique(
                            relation,
                            NameGenerator.joinColumnNames(append(unique.columns(), unique.include())),
                            "key",
                            taken);
            case ForeignKey fk ->
                    NameGenerator.unique(relation, NameGenerator.joinColumnNames(fk.columns()), "fkey", taken);
            case Check check -> {
                // PostgreSQL names a check after its column only when it mentions exactly one.
                Set<String> columnNames = table.columns().stream().map(Column::name).collect(Collectors.toSet());
                Set<String> used = Expressions.referencedColumns(check.expression(), columnNames);
                yield NameGenerator.unique(relation, used.size() == 1 ? used.iterator().next() : null, "check", taken);
            }
        };
    }

    private static void requireKeyColumns(Table table, List<String> columns) {
        for (String column : columns) {
            if (table.column(column).isEmpty()) {
                throw CatalogError.undefinedColumn("column \"" + column + "\" named in key does not exist");
            }
        }
    }

    /** Checks both ends of a foreign key and fills in the referenced columns when they were left out. */
    private ForeignKey resolveForeignKey(Table table, ForeignKey fk) {
        String missing = "\" referenced in foreign key constraint does not exist";
        for (String column : fk.columns()) {
            if (table.column(column).isEmpty()) {
                throw CatalogError.undefinedColumn("column \"" + column + missing);
            }
        }
        Table referenced = state.table(fk.referencedTable());
        String referencedName = referenced.name().name();
        List<String> referencedColumns = fk.referencedColumns();
        if (referencedColumns.isEmpty()) {
            referencedColumns =
                    referenced.primaryKey()
                            .map(PrimaryKey::columns)
                            .orElseThrow(
                                    () ->
                                            CatalogError.invalid(
                                                    "there is no primary key for referenced table \""
                                                            + referencedName
                                                            + "\""));
        }
        for (String column : referencedColumns) {
            if (referenced.column(column).isEmpty()) {
                throw CatalogError.undefinedColumn("column \"" + column + missing);
            }
        }
        if (referencedColumns.size() != fk.columns().size()) {
            throw CatalogError.invalid("number of referencing and referenced columns for foreign key disagree");
        }
        if (!hasUniqueKeyOn(referenced, referencedColumns)) {
            throw CatalogError.invalid(
                    "there is no unique constraint matching given keys for referenced table \""
                            + referencedName
                            + "\"");
        }
        return fk.withReferences(fk.columns(), fk.referencedTable(), referencedColumns);
    }

    /** A foreign key must point at columns that a primary key, unique constraint or unique index covers exactly. */
    private boolean hasUniqueKeyOn(Table table, List<String> columns) {
        Set<String> wanted = new HashSet<>(columns);
        boolean byConstraint =
                table.constraints().stream()
                        .filter(CatalogState::isKey)
                        .anyMatch(c -> new HashSet<>(keyColumns(c)).equals(wanted));
        return byConstraint
                || state.indexes.values().stream()
                        .filter(i -> i.table().equals(table.name()) && i.unique() && i.predicate().isEmpty())
                        .filter(i -> i.columns().stream().allMatch(k -> k.column().isPresent()))
                        .anyMatch(
                                i ->
                                        i.columns().stream()
                                                .map(k -> k.column().orElseThrow())
                                                .collect(Collectors.toSet())
                                                .equals(wanted));
    }

    /**
     * The key a foreign key relies on: the earliest primary key or unique constraint over
     * exactly the columns it references. PostgreSQL binds a foreign key to one specific index,
     * so dropping that key needs CASCADE even when another key covers the same columns.
     */
    private static Optional<Constraint> boundKey(Table referenced, ForeignKey fk) {
        Set<String> wanted = new HashSet<>(fk.referencedColumns());
        return referenced.constraints().stream()
                .filter(CatalogState::isKey)
                .filter(c -> new HashSet<>(keyColumns(c)).equals(wanted))
                .findFirst();
    }

    /** A test for the foreign keys that rely on this unique index because no key constraint covers their columns. */
    BiPredicate<QualifiedName, ForeignKey> reliesOnIndex(Index index) {
        boolean usable =
                index.unique()
                        && index.predicate().isEmpty()
                        && index.columns().stream().allMatch(k -> k.column().isPresent());
        if (!usable) {
            return (owner, fk) -> false;
        }
        Set<String> columns =
                index.columns().stream().map(k -> k.column().orElseThrow()).collect(Collectors.toSet());
        return (owner, fk) ->
                fk.referencedTable().equals(index.table())
                        && new HashSet<>(fk.referencedColumns()).equals(columns)
                        && boundKey(state.table(index.table()), fk).isEmpty();
    }

    private static List<String> keyColumns(Constraint constraint) {
        return switch (constraint) {
            case PrimaryKey pk -> pk.columns();
            case Unique unique -> unique.columns();
            case ForeignKey fk -> fk.columns();
            case Check check -> List.of();
        };
    }

    /**
     * {@code ADD CONSTRAINT ... USING INDEX}: an existing unique index becomes the constraint's
     * index. If the constraint is given another name, the index takes that name.
     */
    private void promoteIndex(QualifiedName table, AlterAction.AddConstraintUsingIndex add) {
        QualifiedName key = new QualifiedName(table.schema(), add.index());
        Index index = state.indexes.get(key);
        if (index == null) {
            if (state.constraintIndexOwner(key).isPresent()) {
                throw CatalogError.invalid("index \"" + add.index() + "\" is already associated with a constraint");
            }
            throw CatalogError.undefinedObject("index \"" + add.index() + "\" does not exist");
        }
        if (!index.table().equals(table)) {
            throw CatalogError.invalid(
                    "index \"" + add.index() + "\" does not belong to table \"" + table.name() + "\"");
        }
        if (!index.unique()) {
            throw CatalogError.invalid("\"" + add.index() + "\" is not a unique index");
        }
        if (index.predicate().isPresent()) {
            throw CatalogError.invalid("\"" + add.index() + "\" is a partial index");
        }
        if (index.columns().stream().anyMatch(k -> k.expression().isPresent())) {
            throw CatalogError.invalid("index \"" + add.index() + "\" contains expressions");
        }
        List<String> columns = index.columns().stream().map(k -> k.column().orElseThrow()).toList();
        Optional<String> name = Optional.of(add.name().orElse(add.index()));
        state.indexes.remove(key);
        addConstraint(
                table,
                add.primaryKey()
                        ? new PrimaryKey(name, columns, index.include(), add.deferrability())
                        : new Unique(name, columns, index.include(), index.nullsNotDistinct(), add.deferrability()));
    }

    private void dropConstraint(QualifiedName table, String name, boolean ifExists, boolean cascade) {
        Table current = state.table(table);
        Optional<Constraint> found = current.constraint(name);
        if (found.isEmpty()) {
            if (ifExists) {
                return;
            }
            throw CatalogError.undefinedObject(constraintOf(name, table) + " does not exist");
        }
        Constraint constraint = found.get();
        if (CatalogState.isKey(constraint)) {
            Table keyed = current;
            BiPredicate<QualifiedName, ForeignKey> reliesOnIt =
                    (owner, fk) ->
                            fk.referencedTable().equals(table) && boundKey(keyed, fk).equals(Optional.of(constraint));
            if (anyForeignKey(reliesOnIt)) {
                if (!cascade) {
                    throw CatalogError.dependentObjects(
                            "cannot drop constraint " + name + " on table " + table.name()
                                    + " because other objects depend on it");
                }
                removeForeignKeys(reliesOnIt);
                current = state.table(table);
            }
        }
        state.tables.put(
                table,
                current.withConstraints(current.constraints().stream().filter(c -> !c.equals(constraint)).toList()));
    }

    private void validateConstraint(QualifiedName table, String name) {
        Table current = state.table(table);
        Constraint constraint =
                current.constraint(name)
                        .orElseThrow(() -> CatalogError.undefinedObject(constraintOf(name, table) + " does not exist"));
        Constraint validated =
                switch (constraint) {
                    case ForeignKey fk -> fk.withNotValid(false);
                    case Check check -> check.withNotValid(false);
                    case PrimaryKey pk -> throw notValidatable(name, table);
                    case Unique unique -> throw notValidatable(name, table);
                };
        replaceConstraint(table, constraint, validated);
    }

    private static CatalogError notValidatable(String name, QualifiedName table) {
        return CatalogError.invalid(constraintOf(name, table) + " is not a foreign key or check constraint");
    }

    private void renameConstraint(QualifiedName table, String from, String to) {
        Table current = state.table(table);
        Constraint constraint =
                current.constraint(from)
                        .orElseThrow(
                                () ->
                                        CatalogError.undefinedObject(
                                                "constraint \"" + from + "\" for table \"" + table.name()
                                                        + "\" does not exist"));
        if (current.constraint(to).isPresent()) {
            throw CatalogError.duplicateObject(
                    "constraint \"" + to + "\" for relation \"" + table.name() + "\" already exists");
        }
        if (CatalogState.isKey(constraint) && state.relationExists(new QualifiedName(table.schema(), to))) {
            throw CatalogError.duplicateObject("relation \"" + to + "\" already exists");
        }
        replaceConstraint(table, constraint, constraint.withName(Optional.of(to)));
    }

    // ---------------------------------------------------------------- RENAME TO, SET SCHEMA

    /**
     * Moves a table to a new name or schema. Indexes and owned sequences follow the table into a
     * new schema; names PostgreSQL generated from the old table name are left as they are, which
     * is what the server does.
     */
    private QualifiedName moveTable(QualifiedName from, QualifiedName to) {
        if (state.relationExists(to)) {
            String where = to.schema().equals(from.schema()) ? "" : " in schema \"" + to.schema() + "\"";
            throw CatalogError.duplicateObject("relation \"" + to.name() + "\" already exists" + where);
        }
        Table current = state.tables.remove(from);
        List<Column> columns = current.columns();

        for (Sequence sequence : List.copyOf(state.sequences.values())) {
            if (!sequence.ownedBy().map(ColumnRef::table).equals(Optional.of(from))) {
                continue;
            }
            QualifiedName oldName = sequence.name();
            QualifiedName newName = new QualifiedName(to.schema(), oldName.name());
            ColumnRef owner = new ColumnRef(to, sequence.ownedBy().orElseThrow().column());
            state.sequences.remove(oldName);
            state.sequences.put(newName, sequence.withName(newName).withOwnedBy(Optional.of(owner)));
            if (!newName.equals(oldName)) {
                columns =
                        columns.stream()
                                .map(
                                        c ->
                                                c.defaultValue().filter(d -> usesSequence(d, oldName)).isPresent()
                                                        ? c.withDefaultValue(Optional.of(nextval(newName)))
                                                        : c)
                                .toList();
            }
        }
        state.tables.put(to, current.withName(to).withColumns(columns));

        for (Index index : List.copyOf(state.indexes.values())) {
            if (index.table().equals(from)) {
                String name = index.name().orElseThrow();
                state.indexes.remove(new QualifiedName(from.schema(), name));
                state.indexes.put(
                        new QualifiedName(to.schema(), name),
                        new Index(
                                index.name(), to, index.unique(), index.method(), index.columns(),
                                index.include(), index.nullsNotDistinct(), index.predicate()));
            }
        }
        mapForeignKeys(
                fk ->
                        fk.referencedTable().equals(from)
                                ? fk.withReferences(fk.columns(), to, fk.referencedColumns())
                                : fk);
        return to;
    }

    // ---------------------------------------------------------------- shared

    boolean anyForeignKey(BiPredicate<QualifiedName, ForeignKey> test) {
        return state.tables.values().stream()
                .anyMatch(t -> t.constraints().stream().anyMatch(c -> c instanceof ForeignKey fk && test.test(t.name(), fk)));
    }

    void removeForeignKeys(BiPredicate<QualifiedName, ForeignKey> test) {
        state.tables.replaceAll(
                (name, table) ->
                        table.withConstraints(
                                table.constraints().stream()
                                        .filter(c -> !(c instanceof ForeignKey fk && test.test(name, fk)))
                                        .toList()));
    }

    private void mapForeignKeys(UnaryOperator<ForeignKey> change) {
        state.tables.replaceAll(
                (name, table) ->
                        table.withConstraints(
                                table.constraints().stream()
                                        .map(c -> c instanceof ForeignKey fk ? change.apply(fk) : c)
                                        .toList()));
    }

    Column column(QualifiedName table, String name) {
        return state.table(table)
                .column(name)
                .orElseThrow(() -> CatalogError.undefinedColumn(columnOf(name, table) + " does not exist"));
    }

    void replaceColumn(QualifiedName table, Column column) {
        Table current = state.table(table);
        state.tables.put(
                table,
                current.withColumns(
                        current.columns().stream().map(c -> c.name().equals(column.name()) ? column : c).toList()));
    }

    private void replaceConstraint(QualifiedName table, Constraint old, Constraint replacement) {
        Table current = state.table(table);
        state.tables.put(
                table,
                current.withConstraints(
                        current.constraints().stream().map(c -> c.equals(old) ? replacement : c).toList()));
    }

    private static boolean mentions(Expression expression, String column) {
        return !Expressions.referencedColumns(expression, Set.of(column)).isEmpty();
    }

    private static boolean involves(Constraint constraint, String column) {
        return switch (constraint) {
            case PrimaryKey pk -> pk.columns().contains(column) || pk.include().contains(column);
            case Unique unique -> unique.columns().contains(column) || unique.include().contains(column);
            case ForeignKey fk -> fk.columns().contains(column);
            case Check check -> mentions(check.expression(), column);
        };
    }

    private static boolean involves(Index index, String column) {
        return index.include().contains(column)
                || index.predicate().filter(p -> mentions(p, column)).isPresent()
                || index.columns().stream()
                        .anyMatch(
                                k ->
                                        k.column().equals(Optional.of(column))
                                                || k.expression().filter(e -> mentions(e, column)).isPresent());
    }

    private static String columnOf(String column, QualifiedName table) {
        return "column \"" + column + "\" of relation \"" + table.name() + "\"";
    }

    private static String constraintOf(String constraint, QualifiedName table) {
        return "constraint \"" + constraint + "\" of relation \"" + table.name() + "\"";
    }

    private static <T> List<T> append(List<T> list, T item) {
        List<T> result = new ArrayList<>(list);
        result.add(item);
        return result;
    }

    private static <T> List<T> append(List<T> list, List<T> items) {
        List<T> result = new ArrayList<>(list);
        result.addAll(items);
        return result;
    }
}
