package io.github.hanumanthraghavendra.schemaguard.engine.catalog;

import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic;
import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Severity;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Column;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ColumnRef;
import io.github.hanumanthraghavendra.schemaguard.engine.model.DataType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.EnumType;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ForeignKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Index;
import io.github.hanumanthraghavendra.schemaguard.engine.model.IndexColumn;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Sequence;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Table;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.AlterAction;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.Expressions;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.ParseResult;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.Parser;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserOptions;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Builds a {@link io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog} by applying statements in order, the way a server would
 * run a script against an empty database.
 *
 * <p>Each statement is all-or-nothing, as in PostgreSQL: if any part of it cannot be applied,
 * the schema is left as it was before the statement, an error is recorded, and the next
 * statement runs.
 */
public final class CatalogBuilder {

    private final CatalogState state = new CatalogState();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final TableOperations tables;

    private CatalogBuilder(ParserOptions options) {
        this.tables = new TableOperations(state, options);
    }

    /** Parses a script and builds its catalog. The result carries parser and catalog diagnostics. */
    public static CatalogResult fromSql(String sql) {
        return fromSql(sql, ParserOptions.defaults());
    }

    public static CatalogResult fromSql(String sql, ParserOptions options) {
        ParseResult parsed = Parser.parse(sql, options);
        CatalogResult built = build(parsed.statements(), options);
        List<Diagnostic> all = new ArrayList<>(parsed.diagnostics());
        all.addAll(built.diagnostics());
        return new CatalogResult(built.catalog(), all);
    }

    public static CatalogResult build(List<Statement> statements, ParserOptions options) {
        Objects.requireNonNull(statements, "statements");
        Objects.requireNonNull(options, "options");
        CatalogBuilder builder = new CatalogBuilder(options);
        for (Statement statement : statements) {
            builder.applyAtomically(statement);
        }
        return new CatalogResult(builder.state.toCatalog(), builder.diagnostics);
    }

    private void applyAtomically(Statement statement) {
        CatalogState saved = state.copy();
        try {
            apply(statement);
        } catch (CatalogError error) {
            state.restore(saved);
            diagnostics.add(new Diagnostic(Severity.ERROR, error.code(), error.getMessage(), statement.span()));
        }
    }

    private void apply(Statement statement) {
        switch (statement) {
            case Statement.CreateTable create -> createTable(create);
            case Statement.AlterTable alter -> alterTable(alter);
            case Statement.CreateIndex create -> createIndex(create);
            case Statement.CreateEnum create -> createEnum(create);
            case Statement.AddEnumValue add -> addEnumValue(add);
            case Statement.RenameEnumValue rename -> renameEnumValue(rename);
            case Statement.CreateSequence create -> createSequence(create);
            case Statement.AlterSequenceOwner alter -> alterSequenceOwner(alter);
            case Statement.Drop drop -> drop(drop);
            case Statement.Ignored ignored -> {
                // Does not change the schema's structure.
            }
            case Statement.Unsupported unsupported -> {
                // Already reported by the parser.
            }
            case Statement.Invalid invalid -> {
                // Already reported by the parser.
            }
        }
    }

    // ---------------------------------------------------------------- tables

    private void createTable(Statement.CreateTable create) {
        if (state.relationExists(create.table().name())) {
            if (create.ifNotExists()) {
                return;
            }
            throw relationExists(create.table().name());
        }
        tables.create(create.table());
    }

    private void alterTable(Statement.AlterTable alter) {
        QualifiedName name = alter.table();
        if (!state.tables.containsKey(name)) {
            if (alter.ifExists()) {
                return;
            }
            throw CatalogError.undefinedObject("relation \"" + name.name() + "\" does not exist");
        }
        for (AlterAction action : alter.actions()) {
            name = tables.apply(name, action);
        }
    }

    // ---------------------------------------------------------------- indexes

    private void createIndex(Statement.CreateIndex create) {
        Index parsed = create.index();
        Table table = state.table(parsed.table());
        List<String> plainColumns = new ArrayList<>(parsed.include());
        parsed.columns().forEach(k -> k.column().ifPresent(plainColumns::add));
        for (String column : plainColumns) {
            if (table.column(column).isEmpty()) {
                throw CatalogError.undefinedColumn("column \"" + column + "\" does not exist");
            }
        }

        String schema = parsed.table().schema();
        String name;
        if (parsed.name().isPresent()) {
            name = parsed.name().get();
            if (state.relationExists(new QualifiedName(schema, name))) {
                if (create.ifNotExists()) {
                    return;
                }
                throw relationExists(new QualifiedName(schema, name));
            }
        } else {
            List<String> parts = new ArrayList<>();
            for (IndexColumn key : parsed.columns()) {
                parts.add(key.column().orElseGet(() -> Expressions.indexColumnName(key.expression().orElseThrow())));
            }
            parts.addAll(parsed.include());
            name =
                    NameGenerator.unique(
                            parsed.table().name(),
                            NameGenerator.joinColumnNames(NameGenerator.distinct(parts)),
                            "idx",
                            n -> state.relationExists(new QualifiedName(schema, n)));
        }
        List<IndexColumn> keys =
                parsed.columns().stream()
                        .map(
                                k ->
                                        new IndexColumn(
                                                k.column(),
                                                k.expression(),
                                                k.collation().flatMap(TableOperations::normaliseCollation),
                                                k.operatorClass(),
                                                k.order(),
                                                k.nulls()))
                        .toList();
        state.indexes.put(
                new QualifiedName(schema, name),
                new Index(
                        Optional.of(name), parsed.table(), parsed.unique(), parsed.method(), keys,
                        parsed.include(), parsed.nullsNotDistinct(), parsed.predicate()));
    }

    // ---------------------------------------------------------------- enumerated types

    private void createEnum(Statement.CreateEnum create) {
        QualifiedName name = create.type().name();
        // A table also defines a type of the same name.
        if (state.enums.containsKey(name) || state.tables.containsKey(name)) {
            throw CatalogError.duplicateObject("type \"" + name.name() + "\" already exists");
        }
        state.enums.put(name, create.type());
    }

    private void addEnumValue(Statement.AddEnumValue add) {
        EnumType type = enumType(add.type());
        List<String> labels = new ArrayList<>(type.labels());
        if (labels.contains(add.value())) {
            if (add.ifNotExists()) {
                return;
            }
            throw CatalogError.duplicateObject("enum label \"" + add.value() + "\" already exists");
        }
        int position = labels.size();
        if (add.before().isPresent()) {
            position = labelPosition(labels, add.before().get());
        } else if (add.after().isPresent()) {
            position = labelPosition(labels, add.after().get()) + 1;
        }
        labels.add(position, add.value());
        state.enums.put(type.name(), new EnumType(type.name(), labels));
    }

    private void renameEnumValue(Statement.RenameEnumValue rename) {
        EnumType type = enumType(rename.type());
        List<String> labels = new ArrayList<>(type.labels());
        int position = labelPosition(labels, rename.from());
        if (labels.contains(rename.to())) {
            throw CatalogError.duplicateObject("enum label \"" + rename.to() + "\" already exists");
        }
        labels.set(position, rename.to());
        state.enums.put(type.name(), new EnumType(type.name(), labels));
    }

    private EnumType enumType(QualifiedName name) {
        EnumType type = state.enums.get(name);
        if (type == null) {
            throw CatalogError.undefinedObject("type \"" + name.name() + "\" does not exist");
        }
        return type;
    }

    private static int labelPosition(List<String> labels, String label) {
        int position = labels.indexOf(label);
        if (position < 0) {
            throw CatalogError.undefinedObject("\"" + label + "\" is not an existing enum label");
        }
        return position;
    }

    // ---------------------------------------------------------------- sequences

    private void createSequence(Statement.CreateSequence create) {
        Sequence sequence = create.sequence();
        if (state.relationExists(sequence.name())) {
            if (create.ifNotExists()) {
                return;
            }
            throw relationExists(sequence.name());
        }
        requireColumn(sequence.ownedBy());
        state.sequences.put(sequence.name(), SequenceDefaults.resolve(sequence));
    }

    private void alterSequenceOwner(Statement.AlterSequenceOwner alter) {
        Sequence sequence = state.sequences.get(alter.sequence());
        if (sequence == null) {
            if (alter.ifExists()) {
                return;
            }
            throw CatalogError.undefinedObject("relation \"" + alter.sequence().name() + "\" does not exist");
        }
        requireColumn(alter.ownedBy());
        state.sequences.put(sequence.name(), sequence.withOwnedBy(alter.ownedBy()));
    }

    private void requireColumn(Optional<ColumnRef> owner) {
        owner.ifPresent(column -> tables.column(column.table(), column.column()));
    }

    // ---------------------------------------------------------------- DROP

    private void drop(Statement.Drop drop) {
        switch (drop.kind()) {
            case TABLE -> dropTables(drop);
            case INDEX -> drop.names().forEach(name -> dropIndex(name, drop.ifExists(), drop.cascade()));
            case TYPE -> drop.names().forEach(name -> dropType(name, drop.ifExists(), drop.cascade()));
            case SEQUENCE -> drop.names().forEach(name -> dropSequence(name, drop.ifExists(), drop.cascade()));
        }
    }

    private void dropTables(Statement.Drop drop) {
        Set<QualifiedName> targets = new LinkedHashSet<>();
        for (QualifiedName name : drop.names()) {
            if (state.tables.containsKey(name)) {
                targets.add(name);
            } else if (!drop.ifExists()) {
                throw CatalogError.undefinedObject("table \"" + name.name() + "\" does not exist");
            }
        }
        for (QualifiedName name : targets) {
            // Foreign keys in tables that are not being dropped too.
            BiPredicate<QualifiedName, ForeignKey> pointsAtIt =
                    (owner, fk) -> fk.referencedTable().equals(name) && !targets.contains(owner);
            if (tables.anyForeignKey(pointsAtIt)) {
                if (!drop.cascade()) {
                    throw CatalogError.dependentObjects(
                            "cannot drop table " + name.name() + " because other objects depend on it");
                }
                tables.removeForeignKeys(pointsAtIt);
            }
        }
        for (QualifiedName name : targets) {
            state.tables.remove(name);
            state.indexes.values().removeIf(index -> index.table().equals(name));
            state.sequences.values().removeIf(s -> s.ownedBy().map(ColumnRef::table).equals(Optional.of(name)));
        }
    }

    private void dropIndex(QualifiedName name, boolean ifExists, boolean cascade) {
        Index index = state.indexes.get(name);
        if (index != null) {
            BiPredicate<QualifiedName, ForeignKey> reliesOnIt = tables.reliesOnIndex(index);
            if (tables.anyForeignKey(reliesOnIt)) {
                if (!cascade) {
                    throw CatalogError.dependentObjects(
                            "cannot drop index " + name.name() + " because other objects depend on it");
                }
                tables.removeForeignKeys(reliesOnIt);
            }
            state.indexes.remove(name);
            return;
        }
        Optional<Table> owner = state.constraintIndexOwner(name);
        if (owner.isPresent()) {
            throw CatalogError.dependentObjects(
                    "cannot drop index " + name.name() + " because constraint " + name.name()
                            + " on table " + owner.get().name().name() + " requires it");
        }
        if (!ifExists) {
            throw CatalogError.undefinedObject("index \"" + name.name() + "\" does not exist");
        }
    }

    private void dropType(QualifiedName name, boolean ifExists, boolean cascade) {
        if (!state.enums.containsKey(name)) {
            if (ifExists) {
                return;
            }
            throw CatalogError.undefinedObject("type \"" + name.name() + "\" does not exist");
        }
        List<ColumnRef> users = new ArrayList<>();
        for (Table table : state.tables.values()) {
            for (Column column : table.columns()) {
                DataType type = column.type();
                if (name.schema().equals(type.schema()) && name.name().equals(type.name())) {
                    users.add(new ColumnRef(table.name(), column.name()));
                }
            }
        }
        if (!users.isEmpty() && !cascade) {
            throw CatalogError.dependentObjects(
                    "cannot drop type " + name.name() + " because other objects depend on it");
        }
        // With CASCADE, PostgreSQL drops the columns of that type.
        users.forEach(user -> tables.dropColumn(user.table(), user.column(), true, true));
        state.enums.remove(name);
    }

    private void dropSequence(QualifiedName name, boolean ifExists, boolean cascade) {
        if (!state.sequences.containsKey(name)) {
            if (ifExists) {
                return;
            }
            throw CatalogError.undefinedObject("sequence \"" + name.name() + "\" does not exist");
        }
        List<ColumnRef> users = new ArrayList<>();
        for (Table table : state.tables.values()) {
            for (Column column : table.columns()) {
                if (column.defaultValue().filter(d -> tables.usesSequence(d, name)).isPresent()) {
                    users.add(new ColumnRef(table.name(), column.name()));
                }
            }
        }
        if (!users.isEmpty() && !cascade) {
            throw CatalogError.dependentObjects(
                    "cannot drop sequence " + name.name() + " because other objects depend on it");
        }
        // With CASCADE, PostgreSQL drops the defaults that use the sequence.
        for (ColumnRef user : users) {
            tables.replaceColumn(
                    user.table(), tables.column(user.table(), user.column()).withDefaultValue(Optional.empty()));
        }
        state.sequences.remove(name);
    }

    private static CatalogError relationExists(QualifiedName name) {
        return CatalogError.duplicateObject("relation \"" + name.name() + "\" already exists");
    }
}
