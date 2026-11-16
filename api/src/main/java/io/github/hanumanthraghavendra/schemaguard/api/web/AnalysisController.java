package io.github.hanumanthraghavendra.schemaguard.api.web;

import io.github.hanumanthraghavendra.schemaguard.api.dto.Requests;
import io.github.hanumanthraghavendra.schemaguard.api.dto.Responses;
import io.github.hanumanthraghavendra.schemaguard.api.service.AnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The analyses. All are stateless: the answer depends on the request alone. */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Analysis", description = "Diff, risk report, migration plan and lint")
// Declaring the error answers here would hide the success answer; this keeps it described.
@ApiResponse(responseCode = "200", description = "The result", useReturnTypeSchema = true)
@ApiResponse(responseCode = "400", description = "The body is not valid JSON or a required field is missing",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "413", description = "An input is larger than the service accepts",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "422", description = "A schema does not parse, or PostgreSQL would refuse it; see diagnostics",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class AnalysisController {

    private final AnalysisService service;

    public AnalysisController(AnalysisService service) {
        this.service = service;
    }

    @PostMapping(path = "/diff", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Every difference between two schemas, including likely renames")
    public Responses.Diff diff(@Valid @RequestBody Requests.Compare request) {
        return service.diff(request);
    }

    @PostMapping(path = "/report", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "What each change would lock or break if applied the direct way, scored from 0 to 100")
    public Responses.Report report(@Valid @RequestBody Requests.Compare request) {
        return service.report(request);
    }

    @PostMapping(path = "/plan", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "The migration from one schema to the other, on the direct or the safe route")
    public Responses.Plan plan(@Valid @RequestBody Requests.Plan request) {
        return service.plan(request);
    }

    @PostMapping(path = "/lint", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "A hand-written migration script, judged statement by statement against its schema")
    public Responses.Lint lint(@Valid @RequestBody Requests.Lint request) {
        return service.lint(request);
    }

    @GetMapping("/rules")
    @Operation(summary = "Every rule: what it checks, why it matters and the safer alternative")
    public List<Responses.Rule> rules() {
        return service.rules();
    }
}
