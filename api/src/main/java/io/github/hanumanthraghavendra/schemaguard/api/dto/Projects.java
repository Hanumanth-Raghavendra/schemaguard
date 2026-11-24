package io.github.hanumanthraghavendra.schemaguard.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Projects and their schema versions: what the project endpoints return. */
public final class Projects {

    private Projects() {}

    @Schema(name = "VersionRisk", description = "The risk of the change from the version before, as scored when the version was saved")
    public record Risk(
            @Schema(description = "From 0 to 100") int score,
            @Schema(description = "NONE, LOW, MEDIUM, HIGH or CRITICAL") String level,
            @Schema(description = "The findings that count most") List<String> reasons,
            @Schema(description = "Significant changes") int changes,
            @Schema(description = "Risks found") int findings) {}

    @Schema(name = "VersionSummary", description = "A saved schema version, without its SQL")
    public record VersionSummary(
            @Schema(description = "1 for a project's first version, then one more each time") int number,
            @Nullable String label,
            Instant createdAt,
            @Schema(description = "The SQL's size in bytes (UTF-8)") int sizeBytes,
            @Schema(description = "Null for the first version, which has nothing before it") @Nullable Risk risk) {}

    @Schema(name = "Version", description = "A saved schema version with its SQL and the SQL of the version before it")
    public record Version(
            int number,
            @Nullable String label,
            Instant createdAt,
            int sizeBytes,
            @Nullable Risk risk,
            String sql,
            @Schema(description = "Null for the first version") @Nullable String previousSql) {}

    @Schema(name = "ProjectSummary", description = "A project in the account's list")
    public record ProjectSummary(UUID id, String name, Instant createdAt, int versionCount, @Nullable VersionSummary latest) {}

    @Schema(name = "Project", description = "A project with its versions, newest first")
    public record Project(UUID id, String name, Instant createdAt, List<VersionSummary> versions) {}
}
