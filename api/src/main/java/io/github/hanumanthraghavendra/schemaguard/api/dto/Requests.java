package io.github.hanumanthraghavendra.schemaguard.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** The bodies the API accepts. Every SQL input is PostgreSQL DDL. */
public final class Requests {

    private Requests() {}

    @Schema(name = "CompareRequest", description = "Two versions of a schema")
    public record Compare(
            @NotNull @Schema(description = "The schema as it is now", example = "CREATE TABLE users (id int PRIMARY KEY);")
                    String before,
            @NotNull
                    @Schema(
                            description = "The schema as it should become",
                            example = "CREATE TABLE users (id int PRIMARY KEY, email text NOT NULL);")
                    String after) {}

    @Schema(name = "PlanRequest", description = "Two versions of a schema and how to plan the way between them")
    public record Plan(
            @NotNull @Schema(description = "The schema as it is now") String before,
            @NotNull @Schema(description = "The schema as it should become") String after,
            @Schema(description = "SAFE avoids long locks; DIRECT is one plain statement per change. Default SAFE")
                    Route route,
            @Schema(description = "Renames a person has confirmed, from the rename candidates of the diff")
                    List<@NotNull @Valid RenameDto> renames,
            @Schema(description = "Value for SET lock_timeout at the top of a safe plan. Default 5s", example = "5s")
                    String lockTimeout) {}

    @Schema(name = "LintRequest", description = "A migration script and the schema it will run on")
    public record Lint(
            @NotNull @Schema(description = "The schema the migration runs on") String schema,
            @NotNull
                    @Schema(
                            description = "The migration script, as it will be run",
                            example = "CREATE INDEX users_email_idx ON users (email);")
                    String migration) {}

    /** Which statements a plan is made of. */
    public enum Route {
        DIRECT,
        SAFE
    }
}
