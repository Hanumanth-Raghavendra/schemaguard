package io.github.hanumanthraghavendra.schemaguard.engine.lint;

import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogBuilder;
import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogResult;
import io.github.hanumanthraghavendra.schemaguard.engine.diagnostics.Diagnostic;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.SchemaDiff;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ForeignKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ObjectKind;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.AlterAction;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.ParseResult;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.Parser;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.ParserOptions;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.Statement;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.DirectRoute;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.LockLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskAnalyzer;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskScore;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleRegistry;
import io.github.hanumanthraghavendra.schemaguard.engine.sql.SqlWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Judges a migration script as it is written, statement by statement.
 *
 * <pre>{@code
 * LintReport report = MigrationLinter.lint(schemaBefore, migrationSql);
 * }</pre>
 *
 * <p>{@link RiskAnalyzer} compares two schemas and has to assume the plain statement for each
 * change. The linter sees the statements themselves, so a script that already builds its
 * index {@code CONCURRENTLY} is not blamed for a lock it does not take.
 *
 * <p>For each statement the linter does three things. It applies the statement to the schema
 * as it is at that point, which gives the exact change the statement makes. It runs the
 * ordinary rules over that one change. And it keeps a finding about a lock only if the
 * statement really is of the kind the finding speaks of. On top of that come the checks only
 * a script allows: a rename, {@code CONCURRENTLY} inside a transaction, a missing
 * {@code lock_timeout}.
 */
public final class MigrationLinter {

    private static final Pattern SETS_LOCK_TIMEOUT = Pattern.compile("(?is)^set\\s+(session\\s+|local\\s+)?lock_timeout\\b.*");
    private static final Set<String> OPENS_TRANSACTION = Set.of("BEGIN", "START");
    private static final Set<String> CLOSES_TRANSACTION = Set.of("COMMIT", "END", "ROLLBACK", "ABORT");

    /** Reported once for the whole script here, not once per statement. */
    private static final String LOCK_TIMEOUT_RULE = "SG-R111";

    private final ParserOptions options;
    private final RuleRegistry rules;
    private final RuleInfo lockTimeoutRule;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final List<StatementLint> statements = new ArrayList<>();
    /** Tables this script created: they have no rows, whatever is done to them. */
    private final Set<QualifiedName> created = new HashSet<>();

    private Catalog catalog;
    private boolean inTransaction;
    private boolean lockTimeoutSet;
    private final Set<QualifiedName> lockedWithoutTimeout = new LinkedHashSet<>();
    private int firstLockedWithoutTimeout = -1;

    private MigrationLinter(Catalog before, ParserOptions options, RuleRegistry registry) {
        this.catalog = before;
        this.options = options;
        this.lockTimeoutRule = registry.rule(LOCK_TIMEOUT_RULE).map(rule -> rule.info()).orElse(null);
        this.rules = lockTimeoutRule == null ? registry : registry.without(Set.of(LOCK_TIMEOUT_RULE));
    }

    /** Lints the script against the schema it is meant to run on, with every standard rule. */
    public static LintReport lint(Catalog before, String migrationSql) {
        return lint(before, migrationSql, ParserOptions.defaults(), RuleRegistry.standard());
    }

    public static LintReport lint(Catalog before, String migrationSql, ParserOptions options, RuleRegistry rules) {
        return new MigrationLinter(before, options, rules).run(migrationSql);
    }

    /**
     * Rules about what the application meets once the migration is over. A statement may
     * break such a rule and a later one mend it, as when a default is dropped and an identity
     * added in its place; only what is still true at the end of the script is reported.
     */
    private static final Set<String> JUDGED_AT_THE_END = Set.of("SG-R501", "SG-R503", "SG-R506");

    private void dropWhatTheScriptPutsRight(Catalog before) {
        Set<String> stillTrue = new HashSet<>();
        for (Finding finding : RiskAnalyzer.analyze(SchemaDiff.compare(before, catalog), rules).findings()) {
            stillTrue.add(finding.rule().id() + finding.message());
        }
        for (int i = 0; i < statements.size(); i++) {
            StatementLint statement = statements.get(i);
            List<LintFinding> kept =
                    statement.findings().stream()
                            .filter(f -> !JUDGED_AT_THE_END.contains(f.rule().id()) || stillTrue.contains(f.rule().id() + f.message()))
                            .toList();
            if (kept.size() != statement.findings().size()) {
                statements.set(
                        i,
                        new StatementLint(statement.span(), statement.sql(), statement.status(), statement.table(), statement.operations(), kept));
            }
        }
    }

    private LintReport run(String sql) {
        Catalog before = catalog;
        ParseResult parsed = Parser.parse(sql, options);
        diagnostics.addAll(parsed.diagnostics());
        for (Statement statement : parsed.statements()) {
            String text = sql.substring(statement.span().startOffset(), statement.span().endOffset());
            statements.add(lint(statement, text));
        }
        dropWhatTheScriptPutsRight(before);
        addLockTimeoutFinding();
        List<LintFinding> all = statements.stream().flatMap(s -> s.findings().stream()).toList();
        return new LintReport(statements, diagnostics, catalog, RiskScore.of(all));
    }

    private StatementLint lint(Statement statement, String text) {
        switch (statement) {
            case Statement.Ignored ignored -> {
                String kind = ignored.kind().split(" ")[0];
                if (OPENS_TRANSACTION.contains(kind)) {
                    inTransaction = true;
                } else if (CLOSES_TRANSACTION.contains(kind)) {
                    inTransaction = false;
                } else if (SETS_LOCK_TIMEOUT.matcher(text).matches()) {
                    lockTimeoutSet = true;
                }
                return new StatementLint(statement.span(), text, StatementLint.Status.NOT_SCHEMA, Optional.empty(), List.of(), List.of());
            }
            case Statement.Unsupported unsupported -> {
                return notAnalysed(statement, text);
            }
            case Statement.Invalid invalid -> {
                return notAnalysed(statement, text);
            }
            default -> {
                return analyse(statement, text);
            }
        }
    }

    private static StatementLint notAnalysed(Statement statement, String text) {
        return new StatementLint(statement.span(), text, StatementLint.Status.NOT_ANALYSED, Optional.empty(), List.of(), List.of());
    }

    private StatementLint analyse(Statement statement, String text) {
        Optional<QualifiedName> table = tableOf(statement);
        List<Operation> operations = new ArrayList<>();
        List<LintFinding> findings = new ArrayList<>();
        boolean failed = false;

        // An ALTER TABLE with several actions is judged action by action.
        for (Statement unit : units(statement)) {
            CatalogResult applied = CatalogBuilder.apply(catalog, List.of(unit), options);
            diagnostics.addAll(applied.diagnostics());
            if (applied.hasErrors()) {
                failed = true;
                continue;
            }
            SchemaDiff change = SchemaDiff.compare(catalog, applied.catalog());
            RuleContext context = new RuleContext(change, created);
            Set<Operation> done = operations(unit, change, context);
            operations.addAll(done);

            Optional<LintFinding> rename = renameFinding(unit);
            if (rename.isPresent()) {
                findings.add(rename.get());
            } else if (!isRename(unit)) {
                for (Finding finding : RiskAnalyzer.analyze(context, rules).findings()) {
                    // A finding about a lock holds only if the statement is the one that takes it.
                    if (finding.operation().isEmpty() || done.contains(finding.operation().get())) {
                        findings.add(new LintFinding(
                                finding.rule(), finding.message(), finding.operation(), finding.certainty(),
                                DirectRoute.referencedTables(finding.change())));
                    }
                }
            }
            if (inTransaction && done.stream().anyMatch(MigrationLinter::isConcurrent)) {
                findings.add(
                        new LintFinding(
                                ScriptRules.CONCURRENTLY_IN_TRANSACTION,
                                "This statement is inside a transaction block, where PostgreSQL refuses CONCURRENTLY.",
                                Optional.empty(),
                                1.0,
                                List.of()));
            }
            noteFullLock(unit, done);
            track(unit);
            catalog = applied.catalog();
        }
        findings.sort(Comparator.comparing(LintFinding::severity).reversed().thenComparing(finding -> finding.rule().id()));
        StatementLint.Status status = failed ? StatementLint.Status.WOULD_FAIL : StatementLint.Status.ANALYSED;
        return new StatementLint(statement.span(), text, status, table, operations, findings);
    }

    private static List<Statement> units(Statement statement) {
        if (statement instanceof Statement.AlterTable alter && alter.actions().size() > 1) {
            List<Statement> units = new ArrayList<>();
            QualifiedName table = alter.table();
            for (AlterAction action : alter.actions()) {
                units.add(new Statement.AlterTable(table, alter.ifExists(), alter.only(), List.of(action), alter.span()));
            }
            return units;
        }
        return List.of(statement);
    }

    // ---------------------------------------------------------------- what a statement does

    /**
     * The kind of statement this is, as far as locks go. Where the statement names a variant,
     * such as CONCURRENTLY or USING INDEX, that decides. Otherwise it is the plain statement
     * for the change it makes, which {@link DirectRoute} knows.
     */
    private static Set<Operation> operations(Statement unit, SchemaDiff change, RuleContext context) {
        Set<Operation> operations = new LinkedHashSet<>();
        switch (unit) {
            case Statement.CreateIndex create ->
                    operations.add(create.concurrently() ? Operation.CREATE_INDEX_CONCURRENTLY : Operation.CREATE_INDEX);
            case Statement.Drop drop when drop.kind() == ObjectKind.INDEX ->
                    operations.add(drop.concurrently() ? Operation.DROP_INDEX_CONCURRENTLY : Operation.DROP_INDEX);
            case Statement.Drop drop when drop.kind() == ObjectKind.TABLE -> operations.add(Operation.DROP_TABLE);
            case Statement.RenameIndex rename -> operations.add(Operation.RENAME_INDEX);
            case Statement.RenameEnumValue rename -> operations.add(Operation.RENAME_ENUM_VALUE);
            case Statement.CreateTable create -> {
                if (create.table().constraints().stream().anyMatch(ForeignKey.class::isInstance)) {
                    operations.add(Operation.CREATE_TABLE_WITH_FOREIGN_KEY);
                }
            }
            case Statement.AlterTable alter -> operations.addAll(operations(alter.actions().get(0), change, context));
            default -> change.changes().forEach(c -> DirectRoute.of(c, context).ifPresent(operations::add));
        }
        return operations;
    }

    private static Set<Operation> operations(AlterAction action, SchemaDiff change, RuleContext context) {
        Set<Operation> operations = new LinkedHashSet<>();
        Class<? extends Change> made =
                switch (action) {
                    case AlterAction.AddConstraintUsingIndex add -> {
                        operations.add(add.primaryKey() ? Operation.ADD_PRIMARY_KEY_USING_INDEX : Operation.ADD_CONSTRAINT_USING_INDEX);
                        yield null;
                    }
                    case AlterAction.RenameColumn rename -> {
                        operations.add(Operation.RENAME_COLUMN);
                        yield null;
                    }
                    case AlterAction.RenameTable rename -> {
                        operations.add(Operation.RENAME_TABLE);
                        yield null;
                    }
                    case AlterAction.SetSchema set -> {
                        operations.add(Operation.SET_SCHEMA);
                        yield null;
                    }
                    case AlterAction.RenameConstraint rename -> {
                        operations.add(Operation.RENAME_CONSTRAINT);
                        yield null;
                    }
                    case AlterAction.AddColumn add -> {
                        // A foreign key on a column that is new has no values to check (measured).
                        boolean foreignKey = add.constraints().stream().anyMatch(ForeignKey.class::isInstance);
                        if (foreignKey) {
                            operations.add(Operation.ADD_COLUMN_WITH_FOREIGN_KEY);
                        }
                        add.constraints().stream()
                                .filter(constraint -> !(constraint instanceof ForeignKey))
                                .forEach(constraint -> operations.add(DirectRoute.addConstraint(constraint)));
                        yield Change.ColumnAdded.class;
                    }
                    case AlterAction.DropColumn drop -> Change.ColumnDropped.class;
                    case AlterAction.AlterColumnType type ->
                            change.changes().stream().anyMatch(Change.ColumnTypeChanged.class::isInstance)
                                    ? Change.ColumnTypeChanged.class
                                    : Change.ColumnCollationChanged.class;
                    case AlterAction.SetDefault set -> Change.ColumnDefaultChanged.class;
                    case AlterAction.DropDefault drop -> Change.ColumnDefaultChanged.class;
                    case AlterAction.SetNotNull set -> Change.ColumnNullabilityChanged.class;
                    case AlterAction.DropNotNull drop -> Change.ColumnNullabilityChanged.class;
                    case AlterAction.AddIdentity add -> Change.ColumnIdentityChanged.class;
                    case AlterAction.DropIdentity drop -> Change.ColumnIdentityChanged.class;
                    case AlterAction.SetIdentity set -> Change.ColumnIdentityChanged.class;
                    case AlterAction.DropExpression drop -> Change.ColumnGeneratedChanged.class;
                    case AlterAction.AddConstraint add -> Change.ConstraintAdded.class;
                    case AlterAction.DropConstraint drop -> Change.ConstraintDropped.class;
                    case AlterAction.ValidateConstraint validate -> Change.ConstraintValidated.class;
                    case AlterAction.SetPersistence set -> Change.TablePersistenceChanged.class;
                    case AlterAction.Inherit inherit -> Change.TableInheritanceChanged.class;
                    case AlterAction.NoInherit inherit -> Change.TableInheritanceChanged.class;
                };
        if (made != null) {
            change.changes().stream()
                    .filter(made::isInstance)
                    .forEach(c -> DirectRoute.of(c, context).ifPresent(operations::add));
        }
        return operations;
    }

    private static boolean isConcurrent(Operation operation) {
        return operation == Operation.CREATE_INDEX_CONCURRENTLY || operation == Operation.DROP_INDEX_CONCURRENTLY;
    }

    /** The table the statement works on, looked up in the schema as it is before the statement. */
    private Optional<QualifiedName> tableOf(Statement statement) {
        return switch (statement) {
            case Statement.Drop drop when drop.kind() == ObjectKind.INDEX && drop.names().size() == 1 ->
                    Optional.ofNullable(catalog.indexes().get(drop.names().get(0))).map(index -> index.table());
            case Statement.RenameIndex rename -> Optional.ofNullable(catalog.indexes().get(rename.index())).map(index -> index.table());
            case Statement.AlterTable alter -> Optional.of(alter.table());
            case Statement.CreateIndex create -> Optional.of(create.index().table());
            case Statement.CreateTable create -> Optional.of(create.table().name());
            case Statement.Drop drop when drop.kind() == ObjectKind.TABLE && drop.names().size() == 1 -> Optional.of(drop.names().get(0));
            default -> Optional.empty();
        };
    }

    // ---------------------------------------------------------------- renames

    private static boolean isRename(Statement unit) {
        if (unit instanceof Statement.RenameIndex || unit instanceof Statement.RenameEnumValue) {
            return true;
        }
        return unit instanceof Statement.AlterTable alter
                && switch (alter.actions().get(0)) {
                    case AlterAction.RenameColumn r -> true;
                    case AlterAction.RenameTable r -> true;
                    case AlterAction.SetSchema s -> true;
                    case AlterAction.RenameConstraint r -> true;
                    default -> false;
                };
    }

    /** The finding for a rename the application can notice; renamed indexes and constraints are not such. */
    private Optional<LintFinding> renameFinding(Statement unit) {
        String message = null;
        Operation operation = null;
        if (unit instanceof Statement.RenameEnumValue rename) {
            message =
                    "Value " + SqlWriter.literal(rename.from()) + " of enum " + rename.type() + " becomes "
                            + SqlWriter.literal(rename.to()) + ". Code that still writes or compares the old value fails.";
            operation = Operation.RENAME_ENUM_VALUE;
        } else if (unit instanceof Statement.AlterTable alter && !created.contains(alter.table())) {
            QualifiedName table = alter.table();
            switch (alter.actions().get(0)) {
                case AlterAction.RenameColumn r -> {
                    message = "Column " + table + "." + r.from() + " becomes " + r.to() + ". Queries that still use the old name fail at once.";
                    operation = Operation.RENAME_COLUMN;
                }
                case AlterAction.RenameTable r -> {
                    message = "Table " + table + " becomes " + r.to() + ". Queries that still use the old name fail at once.";
                    operation = Operation.RENAME_TABLE;
                }
                case AlterAction.SetSchema s -> {
                    message =
                            "Table " + table + " moves to schema " + s.schema()
                                    + ". Queries that name the old schema, or rely on the search path, fail at once.";
                    operation = Operation.SET_SCHEMA;
                }
                default -> {
                    // Renaming a constraint is invisible to queries.
                }
            }
        }
        if (message == null) {
            return Optional.empty();
        }
        return Optional.of(new LintFinding(ScriptRules.RENAMED_IN_PLACE, message, Optional.of(operation), 1.0, List.of()));
    }

    /** Keeps the set of tables this script created correct when one of them is renamed or dropped. */
    private void track(Statement unit) {
        if (unit instanceof Statement.CreateTable create) {
            created.add(create.table().name());
        } else if (unit instanceof Statement.AlterTable alter && created.contains(alter.table())) {
            QualifiedName table = alter.table();
            if (alter.actions().get(0) instanceof AlterAction.RenameTable rename) {
                created.remove(table);
                created.add(new QualifiedName(table.schema(), rename.to()));
            } else if (alter.actions().get(0) instanceof AlterAction.SetSchema set) {
                created.remove(table);
                created.add(new QualifiedName(set.schema(), table.name()));
            }
        }
    }

    // ---------------------------------------------------------------- lock timeout

    private void noteFullLock(Statement unit, Set<Operation> done) {
        Optional<QualifiedName> table = tableOf(unit).filter(name -> catalog.table(name).isPresent() && !created.contains(name));
        boolean fullLock = done.stream().anyMatch(operation -> operation.lock().filter(LockLevel.ACCESS_EXCLUSIVE::equals).isPresent());
        if (!lockTimeoutSet && fullLock && table.isPresent()) {
            lockedWithoutTimeout.add(table.get());
            if (firstLockedWithoutTimeout < 0) {
                firstLockedWithoutTimeout = statements.size();
            }
        }
    }

    /** One finding for the whole script, on the first statement that takes a full lock unprotected. */
    private void addLockTimeoutFinding() {
        if (firstLockedWithoutTimeout < 0 || lockTimeoutRule == null) {
            return;
        }
        StatementLint statement = statements.get(firstLockedWithoutTimeout);
        String names = String.join(", ", lockedWithoutTimeout.stream().map(QualifiedName::toString).toList());
        String message =
                "No lock_timeout is set before this statement. The script locks " + names
                        + " against reads and writes; each such statement first waits for the queries already running"
                        + " there, and blocks all new ones while it waits.";
        List<LintFinding> findings = new ArrayList<>(statement.findings());
        findings.add(new LintFinding(lockTimeoutRule, message, Optional.empty(), 1.0, List.of()));
        statements.set(
                firstLockedWithoutTimeout,
                new StatementLint(statement.span(), statement.sql(), statement.status(), statement.table(), statement.operations(), findings));
    }
}
