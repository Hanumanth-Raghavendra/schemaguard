package io.github.hanumanthraghavendra.schemaguard.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.jspecify.annotations.Nullable;

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

    @Schema(name = "Credentials", description = "An email and a password, to sign up or log in")
    public record Credentials(
            @NotNull @Schema(example = "you@example.com") String email,
            @NotNull
                    @Schema(
                            format = "password",
                            description = "At sign-up: 8 to 256 characters, not one of the 10,000 most common passwords, not the email")
                    String password) {

        @Override
        public String toString() {
            return "Credentials[password hidden]";
        }
    }

    @Schema(name = "PasswordCheck", description = "The account's password, typed again to confirm an action")
    public record PasswordCheck(@NotNull @Schema(format = "password") String password) {

        @Override
        public String toString() {
            return "PasswordCheck[password hidden]";
        }
    }

    @Schema(name = "ProjectName", description = "A project's name: 1 to 80 characters on one line, unique in the account")
    public record ProjectName(@NotNull @Schema(example = "shop") String name) {}

    @Schema(name = "KeyName", description = "An API key's name: 1 to 80 characters on one line, such as where it is used")
    public record KeyName(@NotNull @Schema(example = "GitHub Actions") String name) {}

    @Schema(name = "NewVersion", description = "A schema to save as a project's next version")
    public record NewVersion(
            @NotNull @Schema(description = "The whole schema as SQL, such as pg_dump --schema-only prints it") String sql,
            @Schema(description = "An optional note of at most 120 characters, such as a release or a commit", example = "Release 2.4")
                    @Nullable String label) {}

    /** Which statements a plan is made of. */
    public enum Route {
        DIRECT,
        SAFE
    }
}
