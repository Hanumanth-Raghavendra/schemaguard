package io.github.hanumanthraghavendra.schemaguard.engine.lint;

import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation;
import io.github.hanumanthraghavendra.schemaguard.engine.source.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the linter has to say about one statement of the script.
 *
 * @param span       where the statement is in the script
 * @param sql        the statement as written, without the semicolon
 * @param status     whether the statement was judged, and if not, why
 * @param table      the table the statement works on, if it works on one
 * @param operations what the statement does to that table, one entry per action of an
 *                   {@code ALTER TABLE} with several; each tells the lock taken and the work done
 * @param findings   the risks, most severe first
 */
public record StatementLint(
        SourceSpan span,
        String sql,
        Status status,
        Optional<QualifiedName> table,
        List<Operation> operations,
        List<LintFinding> findings) {

    /** What became of a statement. */
    public enum Status {
        /** Read, applied to the schema, and judged. */
        ANALYSED,
        /** Does not change the schema: {@code SET}, {@code BEGIN}, {@code UPDATE} and the like. */
        NOT_SCHEMA,
        /** Not understood, or of a kind the engine does not model. Nothing is known about its risk. */
        NOT_ANALYSED,
        /** PostgreSQL would refuse it on this schema; see the diagnostics. */
        WOULD_FAIL
    }

    public StatementLint {
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(table, "table");
        operations = List.copyOf(operations);
        findings = List.copyOf(findings);
    }
}
