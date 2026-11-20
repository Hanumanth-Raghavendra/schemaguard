package io.github.hanumanthraghavendra.schemaguard.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The bodies the API returns. Each carries a {@code text} form ready to show as it is. */
public final class Responses {

    private Responses() {}

    @Schema(name = "Diagnostic", description = "A problem found in one of the SQL inputs")
    public record Diagnostic(
            @Schema(description = "Which input: before, after, schema or migration") String input,
            @Schema(description = "ERROR or WARNING") String severity,
            @Schema(example = "SG-PARSE-001") String code,
            String message,
            int line,
            int column) {}

    @Schema(name = "Change", description = "One difference between the two schemas")
    public record Change(
            @Schema(example = "ColumnAdded") String kind,
            @Schema(example = "+ column public.users.email text NOT NULL") String summary,
            @Schema(description = "True if the change is only a different way of writing the same thing") boolean cosmetic,
            @Schema(description = "The table the change belongs to, if any") @Nullable NameDto table,
            @Schema(description = "For a rename candidate: the rename to send back to /plan once confirmed")
                    @Nullable RenameDto rename,
            @Schema(description = "For a rename candidate: how likely the rename is, from 0 to 1") @Nullable Double confidence) {}

    @Schema(name = "DiffResponse")
    public record Diff(
            List<Change> changes,
            @Schema(description = "Number of changes that are not cosmetic") int significant,
            List<Diagnostic> warnings,
            String text) {}

    @Schema(name = "Operation", description = "A kind of statement and what it does to a table, as measured on PostgreSQL 16")
    public record Operation(
            @Schema(example = "CREATE_INDEX") String name,
            @Schema(example = "CREATE INDEX") String statement,
            @Schema(description = "Lock on the table, or null", example = "SHARE") @Nullable String lock,
            @Schema(description = "What the lock blocks, or null", example = "blocks writes, reads continue") @Nullable String lockEffect,
            @Schema(description = "Lock on tables it references through a foreign key, or null") @Nullable String referencedLock,
            @Schema(description = "NONE, SCAN or REWRITE") String work) {}

    @Schema(name = "Finding", description = "One risk")
    public record Finding(
            @Schema(example = "SG-R101") String ruleId,
            @Schema(example = "index-added-without-concurrently") String rule,
            String category,
            @Schema(description = "LOW, MEDIUM, HIGH or CRITICAL") String severity,
            String title,
            String message,
            String why,
            String fix,
            @Schema(description = "From 1 to 100") int score,
            @Schema(description = "From 0 to 1") double certainty,
            @Nullable NameDto table,
            @Nullable Operation operation,
            @Schema(description = "What the statement locks and for how long, in one line") @Nullable String effect,
            @Schema(description = "The change the finding is about (risk reports only)") @Nullable String change) {}

    @Schema(name = "Score")
    public record Score(
            @Schema(description = "From 0 to 100") int value,
            @Schema(description = "NONE, LOW, MEDIUM, HIGH or CRITICAL") String level,
            @Schema(description = "The findings that count most") List<String> reasons) {}

    @Schema(name = "ReportResponse")
    public record Report(Score score, List<Finding> findings, List<Diagnostic> warnings, String text) {}

    @Schema(name = "Step", description = "One statement of a migration plan")
    public record Step(
            String sql,
            @Nullable Operation operation,
            @Nullable NameDto table,
            @Schema(description = "False if the statement must run outside a transaction block") boolean transactional,
            String note,
            @Schema(description = "Risks the plan could not remove; each needs a decision") List<String> cautions) {}

    @Schema(name = "Unplanned", description = "A change PostgreSQL has no statement for")
    public record Unplanned(String change, String reason) {}

    @Schema(name = "PlanResponse")
    public record Plan(
            String route,
            @Schema(description = "True if running the steps reaches the new schema") boolean complete,
            @Schema(description = "True if some step carries a caution") boolean needsDecisions,
            List<Step> steps,
            List<Unplanned> unplanned,
            List<Diagnostic> warnings,
            @Schema(description = "The plan as a script for psql") String sql) {}

    @Schema(name = "LintedStatement", description = "One statement of the script and what was found in it")
    public record Statement(
            int line,
            int column,
            String sql,
            @Schema(description = "ANALYSED, NOT_SCHEMA, NOT_ANALYSED or WOULD_FAIL") String status,
            @Nullable NameDto table,
            List<Operation> operations,
            List<Finding> findings) {}

    @Schema(name = "LintResponse")
    public record Lint(
            Score score,
            List<Statement> statements,
            @Schema(description = "Problems in the migration script") List<Diagnostic> diagnostics,
            @Schema(description = "Warnings about the schema") List<Diagnostic> warnings,
            String text) {}

    @Schema(name = "ModelColumn", description = "A column of the merged model")
    public record ModelColumn(
            String name,
            @Schema(description = "Type as SQL, from the new schema unless the column was removed", example = "bigint")
                    String type,
            boolean notNull,
            boolean primaryKey,
            @Schema(allowableValues = {"ADDED", "REMOVED", "CHANGED", "UNCHANGED"}) String status,
            @Schema(description = "The old type, when the type changed", example = "integer") @Nullable String previousType) {}

    @Schema(name = "ModelTable", description = "A table of either schema")
    public record ModelTable(
            NameDto name,
            @Schema(allowableValues = {"ADDED", "REMOVED", "CHANGED", "UNCHANGED"}) String status,
            @Schema(description = "The new schema's columns in their order, then the removed ones") List<ModelColumn> columns) {}

    @Schema(name = "ModelReference", description = "A foreign key of either schema")
    public record ModelReference(
            @Schema(description = "Constraint name, or null") @Nullable String name,
            @Schema(description = "The table that holds the key") NameDto from,
            List<String> columns,
            @Schema(description = "The referenced table") NameDto to,
            List<String> referencedColumns,
            @Schema(allowableValues = {"ADDED", "REMOVED", "CHANGED", "UNCHANGED"}) String status) {}

    @Schema(name = "ModelResponse")
    public record Model(
            @Schema(description = "Every table of either schema, sorted by name") List<ModelTable> tables,
            List<ModelReference> references,
            List<Diagnostic> warnings) {}

    @Schema(name = "Rule")
    public record Rule(
            String id,
            String name,
            String category,
            String severity,
            String title,
            String why,
            String fix,
            @Schema(description = "schema: judged from two schemas; script: only when a script is linted") String scope) {}
}
