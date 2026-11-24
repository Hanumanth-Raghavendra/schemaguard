package io.github.hanumanthraghavendra.schemaguard.api.keys;

import io.github.hanumanthraghavendra.schemaguard.api.auth.CurrentAccount;
import io.github.hanumanthraghavendra.schemaguard.api.auth.SecurityConfig;
import io.github.hanumanthraghavendra.schemaguard.api.dto.Keys;
import io.github.hanumanthraghavendra.schemaguard.api.dto.Requests;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The logged-in account's API keys. Only a login reaches these endpoints: a key cannot make or list keys. */
@RestController
@RequestMapping(path = "/api/v1/account/keys", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
        name = "API keys",
        description = "Keys for the command-line tool and CI: sent as Authorization: Bearer sg_..., they may read the"
                + " account's projects and save versions, nothing else. A key is shown once, when it is made.")
@SecurityRequirement(name = SecurityConfig.BEARER)
@ApiResponse(responseCode = "401", description = "No access token, or one that is expired or invalid",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "403", description = "The request came with an API key; keys are managed with a login",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "503", description = "API keys are switched off on this server or the database is unreachable",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class ApiKeyController {

    private final ApiKeyService keys;

    public ApiKeyController(ApiKeyService keys) {
        this.keys = keys;
    }

    @GetMapping
    @Operation(operationId = "listApiKeys", summary = "The account's API keys, newest first, without their values")
    @ApiResponse(responseCode = "200", description = "The keys", useReturnTypeSchema = true)
    public List<Keys.ApiKey> list(@Parameter(hidden = true) Authentication authentication) {
        return keys.list(CurrentAccount.id(authentication)).stream().map(ApiKeyController::view).toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createApiKey", summary = "Make an API key", description = "The answer holds the whole key, once: copy it now.")
    @ApiResponse(responseCode = "201", description = "Made; Location names it", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "422", description = "The name cannot be used, or the account has 10 keys already",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Keys.NewApiKey> create(
            @Parameter(hidden = true) Authentication authentication, @Valid @RequestBody Requests.KeyName body) {
        ApiKeyService.Created created = keys.create(CurrentAccount.id(authentication), body.name());
        ApiKey key = created.key();
        return ResponseEntity.created(URI.create("/api/v1/account/keys/" + key.id()))
                .body(new Keys.NewApiKey(key.id(), key.name(), key.hint(), key.createdAt(), created.secret()));
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "deleteApiKey", summary = "Delete an API key", description = "Anything still using it gets 401 from then on.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "This account has no such key",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> delete(@Parameter(hidden = true) Authentication authentication, @PathVariable UUID id) {
        keys.delete(CurrentAccount.id(authentication), id);
        return ResponseEntity.noContent().build();
    }

    private static Keys.ApiKey view(ApiKey key) {
        return new Keys.ApiKey(key.id(), key.name(), key.hint(), key.createdAt(), key.lastUsedAt());
    }
}
