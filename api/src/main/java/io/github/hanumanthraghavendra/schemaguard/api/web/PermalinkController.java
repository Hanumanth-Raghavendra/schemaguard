package io.github.hanumanthraghavendra.schemaguard.api.web;

import io.github.hanumanthraghavendra.schemaguard.api.dto.Permalinks;
import io.github.hanumanthraghavendra.schemaguard.api.dto.Requests;
import io.github.hanumanthraghavendra.schemaguard.api.service.PermalinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.function.Function;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Saved analyses. Saving runs the same analysis as the matching endpoint under
 * {@code /api/v1}, keeps the request and the result, and returns an id to share.
 */
@RestController
@RequestMapping(path = "/api/v1/analyses", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
        name = "Saved analyses",
        description =
                "Permalinks. Saving stores the SQL you send for 30 days, so do not save schemas that must stay private."
                        + " Anyone with the id can open the analysis; only the holder of the delete token can delete it.")
@ApiResponse(responseCode = "404", description = "No saved analysis has this id, or it has expired",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "503", description = "Saved analyses are switched off on this server or the database is unreachable",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class PermalinkController {

    private static final String HEADER = "X-Delete-Token";

    private final PermalinkService service;

    public PermalinkController(PermalinkService service) {
        this.service = service;
    }

    @PostMapping(path = "/diff", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Compute a diff and save it")
    @ApiResponse(responseCode = "201", description = "Saved", useReturnTypeSchema = true)
    public ResponseEntity<Permalinks.Saved> diff(@Valid @RequestBody Requests.Compare request) {
        return created(request, service::saveDiff);
    }

    @PostMapping(path = "/report", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Compute a risk report and save it")
    @ApiResponse(responseCode = "201", description = "Saved", useReturnTypeSchema = true)
    public ResponseEntity<Permalinks.Saved> report(@Valid @RequestBody Requests.Compare request) {
        return created(request, service::saveReport);
    }

    @PostMapping(path = "/plan", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Compute a migration plan and save it")
    @ApiResponse(responseCode = "201", description = "Saved", useReturnTypeSchema = true)
    public ResponseEntity<Permalinks.Saved> plan(@Valid @RequestBody Requests.Plan request) {
        return created(request, service::savePlan);
    }

    @PostMapping(path = "/lint", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Lint a migration script and save the result")
    @ApiResponse(responseCode = "201", description = "Saved", useReturnTypeSchema = true)
    public ResponseEntity<Permalinks.Saved> lint(@Valid @RequestBody Requests.Lint request) {
        return created(request, service::saveLint);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Open a saved analysis")
    @ApiResponse(responseCode = "200", description = "The saved analysis", useReturnTypeSchema = true)
    public Permalinks.Stored open(@PathVariable String id) {
        return service.open(id);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a saved analysis before it expires")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "403", description = "The delete token does not match",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> delete(
            @PathVariable String id,
            @Parameter(description = "The token returned when the analysis was saved") @RequestHeader(name = HEADER, required = false)
                    String token) {
        service.delete(id, token);
        return ResponseEntity.noContent().build();
    }

    private static <Q> ResponseEntity<Permalinks.Saved> created(Q request, Function<Q, Permalinks.Saved> save) {
        Permalinks.Saved saved = save.apply(request);
        return ResponseEntity.created(URI.create(saved.path())).body(saved);
    }
}
