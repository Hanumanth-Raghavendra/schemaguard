package io.github.hanumanthraghavendra.schemaguard.engine.planner;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.ChangeFormatter;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.SchemaDiff;
import java.util.List;
import java.util.Objects;

/**
 * An ordered list of statements that turns the old schema into the new one.
 *
 * @param route     the route the plan takes
 * @param diff      the changes the plan applies. Confirmed renames are already taken out of
 *                  it: what would have been a drop and an add is no longer there
 * @param steps     the statements, in the order they have to run
 * @param unplanned changes PostgreSQL has no statement for; the plan does not apply them
 */
public record MigrationPlan(PlanOptions.Route route, SchemaDiff diff, List<Step> steps, List<Unplanned> unplanned) {

    /**
     * A change the plan leaves out.
     *
     * @param change the change
     * @param reason why no statement applies it, and what to do instead
     */
    public record Unplanned(Change change, String reason) {}

    public MigrationPlan {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(diff, "diff");
        steps = List.copyOf(steps);
        unplanned = List.copyOf(unplanned);
    }

    /** True if running the steps gives exactly the new schema, apart from column order. */
    public boolean isComplete() {
        return unplanned.isEmpty();
    }

    /** True if some step keeps a risk that a person has to decide about. */
    public boolean needsDecisions() {
        return steps.stream().anyMatch(step -> !step.cautions().isEmpty());
    }

    /**
     * The plan as a script for {@code psql}. It must not be wrapped in one transaction when
     * it has a step that is not transactional; such steps are marked in the script.
     */
    public String toSql() {
        StringBuilder out = new StringBuilder();
        out.append("-- SchemaGuard migration plan, ").append(route == PlanOptions.Route.SAFE ? "safe" : "direct");
        out.append(" route: ").append(steps.size()).append(steps.size() == 1 ? " statement" : " statements").append('\n');
        for (Step step : steps) {
            out.append('\n');
            if (!step.note().isEmpty()) {
                out.append("-- ").append(step.note()).append('\n');
            }
            if (!step.transactional()) {
                out.append("-- Run outside a transaction block.\n");
            }
            step.cautions().forEach(caution -> out.append("-- CAUTION: ").append(caution).append('\n'));
            out.append(step.sql()).append(";\n");
        }
        if (!unplanned.isEmpty()) {
            out.append("\n-- NOT PLANNED: the following changes need manual work.\n");
            for (Unplanned item : unplanned) {
                out.append("--   ").append(ChangeFormatter.format(item.change())).append('\n');
                out.append("--     ").append(item.reason()).append('\n');
            }
        }
        return out.toString();
    }
}
