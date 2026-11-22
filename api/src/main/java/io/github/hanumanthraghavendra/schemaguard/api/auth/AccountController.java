package io.github.hanumanthraghavendra.schemaguard.api.auth;

import io.github.hanumanthraghavendra.schemaguard.api.dto.Accounts;
import io.github.hanumanthraghavendra.schemaguard.api.dto.Requests;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The logged-in account. Every endpoint here needs an access token. */
@RestController
@RequestMapping(path = "/api/v1/account", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Accounts")
@SecurityRequirement(name = SecurityConfig.BEARER)
@ApiResponse(responseCode = "401", description = "No access token, or one that is expired, invalid, or names a deleted account",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "503", description = "Accounts are switched off on this server or the database is unreachable",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    @Operation(operationId = "getAccount", summary = "The logged-in account")
    @ApiResponse(responseCode = "200", description = "The account", useReturnTypeSchema = true)
    public Accounts.Profile account(@AuthenticationPrincipal Jwt token) {
        return AuthController.profile(accounts.account(UUID.fromString(token.getSubject())));
    }

    @DeleteMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "deleteAccount",
            summary = "Delete the account",
            description = "Deletes the account and ends all its sessions. Needs the password.")
    @ApiResponse(responseCode = "204", description = "Deleted; the refresh cookie is cleared")
    @ApiResponse(responseCode = "403", description = "The password is wrong",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429",
            description = "Too many wrong passwords for this account; the Retry-After header says when to try again",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt token, @Valid @RequestBody Requests.PasswordCheck check) {
        accounts.delete(UUID.fromString(token.getSubject()), check.password());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, RefreshCookie.clear().toString()).build();
    }
}
