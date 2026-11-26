package io.github.hanumanthraghavendra.schemaguard.api.web;

import io.github.hanumanthraghavendra.schemaguard.api.dto.Responses;
import io.github.hanumanthraghavendra.schemaguard.api.service.AnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The rule catalogue. Apart from {@link AnalysisController}, whose error answers (400, 413, 422,
 * 429) are declared for the whole class and cannot happen here.
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Analysis", description = "Diff, risk report, migration plan and lint")
public class RulesController {

    private final AnalysisService service;

    public RulesController(AnalysisService service) {
        this.service = service;
    }

    @GetMapping("/rules")
    @Operation(summary = "Every rule: what it checks, why it matters and the safer alternative")
    @ApiResponse(responseCode = "200", description = "The rules")
    public List<Responses.Rule> rules() {
        return service.rules();
    }
}
