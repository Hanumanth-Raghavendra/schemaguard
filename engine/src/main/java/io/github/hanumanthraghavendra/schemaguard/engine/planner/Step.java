package io.github.hanumanthraghavendra.schemaguard.engine.planner;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One statement of a migration plan.
 *
 * @param sql           the statement, without the closing semicolon
 * @param operation     what kind of statement it is; this tells which lock it takes and how
 *                      much work it does while holding it. Empty for statements that lock
 *                      no existing table, such as {@code CREATE TABLE}
 * @param table         the table the statement works on, if it works on one
 * @param transactional false if PostgreSQL refuses the statement inside a transaction
 *                      block ({@code CREATE INDEX CONCURRENTLY}), or if a later statement
 *                      needs it committed first ({@code ALTER TYPE ... ADD VALUE})
 * @param note          why the step is there, in a few words; may be empty
 * @param cautions      risks the plan could not remove from this step. Each one needs a
 *                      decision by a person before the step is run
 * @param change        the change this step helps to apply; empty for settings
 */
public record Step(
        String sql,
        Optional<Operation> operation,
        Optional<QualifiedName> table,
        boolean transactional,
        String note,
        List<String> cautions,
        Optional<Change> change) {

    public Step {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(note, "note");
        Objects.requireNonNull(change, "change");
        cautions = List.copyOf(cautions);
    }

    Step withCautions(List<String> value) {
        return new Step(sql, operation, table, transactional, note, value, change);
    }
}
