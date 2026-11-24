package io.github.hanumanthraghavendra.schemaguard.api.projects;

import io.github.hanumanthraghavendra.schemaguard.api.auth.SecurityConfig;
import io.github.hanumanthraghavendra.schemaguard.api.dto.Projects;
import io.github.hanumanthraghavendra.schemaguard.api.dto.Requests;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The logged-in account's projects and their schema versions. Every endpoint needs an access token. */
@RestController
@RequestMapping(path = "/api/v1/projects", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
        name = "Projects",
        description = "Named schemas of the logged-in account, each a numbered series of versions. Saving a version"
                + " stores the risk of the change from the one before, so the timeline shows how risky each step was."
                + " Another account's project answers 404, like a missing one.")
@SecurityRequirement(name = SecurityConfig.BEARER)
@ApiResponse(responseCode = "401", description = "No access token, or one that is expired or invalid",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "503", description = "Projects are switched off on this server or the database is unreachable",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class ProjectController {

    private final ProjectService projects;

    public ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    @GetMapping
    @Operation(operationId = "listProjects", summary = "The account's projects, most recently changed first")
    @ApiResponse(responseCode = "200", description = "The projects", useReturnTypeSchema = true)
    public List<Projects.ProjectSummary> list(@AuthenticationPrincipal Jwt token) {
        return projects.list(account(token)).stream()
                .map(listing -> new Projects.ProjectSummary(
                        listing.project().id(),
                        listing.project().name(),
                        listing.project().createdAt(),
                        listing.versions(),
                        listing.latest() == null ? null : summary(listing.latest())))
                .toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createProject", summary = "Create a project")
    @ApiResponse(responseCode = "201", description = "Created, empty; Location names it", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "409", description = "The account has a project of that name already",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "The name cannot be used, or the account has all the projects it may have",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Projects.Project> create(@AuthenticationPrincipal Jwt token, @Valid @RequestBody Requests.ProjectName body) {
        ProjectService.Detail created = projects.create(account(token), body.name());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + created.project().id())).body(project(created));
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getProject", summary = "A project with its versions, newest first")
    @ApiResponse(responseCode = "200", description = "The project", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "No such project in this account",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public Projects.Project get(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        return project(projects.get(account(token), id));
    }

    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "renameProject", summary = "Rename a project")
    @ApiResponse(responseCode = "200", description = "Renamed", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "No such project in this account",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Another project of the account has that name",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "The name cannot be used",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public Projects.Project rename(
            @AuthenticationPrincipal Jwt token, @PathVariable UUID id, @Valid @RequestBody Requests.ProjectName body) {
        return project(projects.rename(account(token), id, body.name()));
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "deleteProject", summary = "Delete a project with all its versions")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "No such project in this account",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        projects.delete(account(token), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/{id}/versions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "addVersion",
            summary = "Save a schema as the project's next version",
            description = "Compares it with the latest version and stores the risk of that change. A schema the same as the"
                    + " latest version is refused (409), so saving on every push keeps only real changes.")
    @ApiResponse(responseCode = "201", description = "Saved; Location names the version", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "No such project in this account",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The same as the latest version, or another version was saved at the same moment",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "413", description = "The schema is longer than the service accepts",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422",
            description = "The schema has errors (diagnostics say where), the label cannot be used, or the project or account is full",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "507", description = "The service has no room for more versions right now",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Projects.VersionSummary> addVersion(
            @AuthenticationPrincipal Jwt token, @PathVariable UUID id, @Valid @RequestBody Requests.NewVersion body) {
        SavedVersion saved = projects.addVersion(account(token), id, body.sql(), body.label());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + id + "/versions/" + saved.number())).body(summary(saved));
    }

    @GetMapping("/{id}/versions/{number}")
    @Operation(operationId = "getVersion", summary = "A version with its SQL and the SQL of the version before it")
    @ApiResponse(responseCode = "200", description = "The version", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "No such project in this account, or no such version",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public Projects.Version version(@AuthenticationPrincipal Jwt token, @PathVariable UUID id, @PathVariable int number) {
        ProjectService.Opened opened = projects.version(account(token), id, number);
        SavedVersion v = opened.version();
        return new Projects.Version(v.number(), v.label(), v.createdAt(), v.sizeBytes(), risk(v.risk()), opened.sql(), opened.previousSql());
    }

    @DeleteMapping("/{id}/versions/{number}")
    @Operation(operationId = "deleteVersion", summary = "Delete the latest version",
            description = "Only the latest version can be deleted: each version's risk is measured against the one before it.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "No such project in this account, or no such version",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Not the latest version",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> deleteVersion(@AuthenticationPrincipal Jwt token, @PathVariable UUID id, @PathVariable int number) {
        projects.deleteVersion(account(token), id, number);
        return ResponseEntity.noContent().build();
    }

    private static UUID account(Jwt token) {
        return UUID.fromString(token.getSubject());
    }

    private static Projects.Project project(ProjectService.Detail detail) {
        Project p = detail.project();
        return new Projects.Project(p.id(), p.name(), p.createdAt(), detail.versions().stream().map(ProjectController::summary).toList());
    }

    private static Projects.VersionSummary summary(SavedVersion v) {
        return new Projects.VersionSummary(v.number(), v.label(), v.createdAt(), v.sizeBytes(), risk(v.risk()));
    }

    private static Projects.@Nullable Risk risk(SavedVersion.@Nullable Risk risk) {
        return risk == null ? null : new Projects.Risk(risk.score(), risk.level(), risk.reasons(), risk.changes(), risk.findings());
    }
}
