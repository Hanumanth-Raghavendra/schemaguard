package io.github.hanumanthraghavendra.schemaguard.engine.rules.compatibility;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Check;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Column;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Constraint;
import io.github.hanumanthraghavendra.schemaguard.engine.model.ForeignKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.PrimaryKey;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Table;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Unique;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.Expressions;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Category;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.ChangeRule;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.DirectRoute;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Finding;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskLevel;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleContext;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RuleInfo;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A constraint dropped from a table that stays. A constraint that goes away because its column
 * or the table it refers to is dropped is not reported; that drop is reported instead.
 */
public final class ConstraintDropped extends ChangeRule<Change.ConstraintDropped> {

    public ConstraintDropped() {
        super(
                Change.ConstraintDropped.class,
                new RuleInfo(
                        "SG-R503",
                        "constraint-dropped",
                        Category.COMPATIBILITY,
                        RiskLevel.MEDIUM,
                        "Constraint is dropped",
                        "A constraint is a promise the database keeps for the application. Once it is gone, "
                                + "rows that break the rule can be written, and they stay: adding the constraint back "
                                + "later fails because of them. Dropping a primary key or unique constraint also drops "
                                + "its index, so lookups that used it become slow.",
                        "Drop a constraint only when the rule it enforced is really gone. To change a rule, "
                                + "add the new constraint before dropping the old one."));
    }

    @Override
    protected Optional<Finding> evaluate(Change.ConstraintDropped change, RuleContext context) {
        Constraint constraint = change.constraint();
        Optional<Table> before = context.tableBefore(change.table());
        Optional<Table> after = context.tableAfter(change.table());
        if (before.isEmpty() || after.isEmpty()) {
            return Optional.empty();
        }
        boolean columnsKept = columns(constraint, before.get()).stream().allMatch(c -> after.get().column(c).isPresent());
        boolean targetKept =
                !(constraint instanceof ForeignKey key) || context.after().table(key.referencedTable()).isPresent();
        if (!columnsKept || !targetKept) {
            return Optional.empty();
        }
        String name = constraint.name().orElse("(unnamed)");
        String message =
                switch (constraint) {
                    case PrimaryKey key ->
                            "Dropping primary key " + name + " of " + change.table()
                                    + " also drops its index: rows need not be unique any more and lookups by key get slow.";
                    case Unique key ->
                            "Dropping unique constraint " + name + " of " + change.table()
                                    + " also drops its index: duplicates are accepted from now on.";
                    case ForeignKey key ->
                            "Without foreign key " + name + ", rows of " + change.table() + " can point to rows of "
                                    + key.referencedTable() + " that do not exist.";
                    case Check check ->
                            "Without check constraint " + name + ", " + change.table()
                                    + " accepts rows that break the condition.";
                };
        return DirectRoute.of(change, context).flatMap(operation -> found(change, message, operation));
    }

    /** The columns of the table that the constraint is about. */
    private static Set<String> columns(Constraint constraint, Table table) {
        return switch (constraint) {
            case PrimaryKey key -> Set.copyOf(key.columns());
            case Unique key -> Set.copyOf(key.columns());
            case ForeignKey key -> Set.copyOf(key.columns());
            case Check check ->
                    Expressions.referencedColumns(
                            check.expression(), table.columns().stream().map(Column::name).collect(Collectors.toSet()));
        };
    }
}
