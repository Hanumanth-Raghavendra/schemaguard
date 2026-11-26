package io.github.hanumanthraghavendra.schemaguard.api.auth;

import io.github.hanumanthraghavendra.schemaguard.api.dto.Accounts;
import io.github.hanumanthraghavendra.schemaguard.api.dto.Requests;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sign-up, login and the session: the refresh token comes and goes in a cookie, never in JSON. */
@RestController
@RequestMapping(path = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
        name = "Accounts",
        description = "Sign up, log in, keep a session going and end it. A session answer holds a short-lived access"
                + " token for Authorization: Bearer; the refresh token is set as the httpOnly cookie " + RefreshCookie.NAME
                + ", which a browser sends to /api/v1/auth only. Accounts need the server's database (503 without it).")
@ApiResponse(responseCode = "503", description = "Accounts are switched off on this server or the database is unreachable",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class AuthController {

    private final AccountService accounts;

    public AuthController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping(path = "/signup", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "signUp", summary = "Create an account and log in")
    @ApiResponse(responseCode = "201", description = "Created and logged in; the refresh cookie is set", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "409", description = "An account with this email exists already",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Not an email address, or a password that is too short, too long or too common",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Too many sign-ups from this address; Retry-After says when to try again",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Accounts.Session> signUp(
            @Valid @RequestBody Requests.Credentials credentials,
            @Parameter(hidden = true) @CookieValue(name = RefreshCookie.NAME, required = false) @Nullable String previous) {
        return session(HttpStatus.CREATED, accounts.signUp(credentials.email(), credentials.password(), previous));
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "logIn", summary = "Log in")
    @ApiResponse(responseCode = "200", description = "Logged in; the refresh cookie is set", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "401", description = "The email or password is wrong",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429",
            description = "Too many failed logins for this account, or too many login attempts from this address;"
                    + " Retry-After says when to try again",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Accounts.Session> logIn(
            @Valid @RequestBody Requests.Credentials credentials,
            @Parameter(hidden = true) @CookieValue(name = RefreshCookie.NAME, required = false) @Nullable String previous) {
        return session(HttpStatus.OK, accounts.logIn(credentials.email(), credentials.password(), previous));
    }

    @PostMapping("/refresh")
    @Operation(
            operationId = "refreshSession",
            summary = "Renew the session",
            description = "Exchanges the refresh cookie for a new one and returns a new access token. A refresh token works"
                    + " once: presenting it again later ends the whole session, because only a stolen copy would.")
    @SecurityRequirement(name = SecurityConfig.REFRESH_COOKIE)
    @ApiResponse(responseCode = "200", description = "Renewed; the refresh cookie is replaced", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "401", description = "No session: the cookie is missing, expired, revoked or reused; it is cleared",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Accounts.Session> refresh(
            @Parameter(hidden = true) @CookieValue(name = RefreshCookie.NAME, required = false) @Nullable String token) {
        return session(HttpStatus.OK, accounts.refresh(token));
    }

    @PostMapping("/logout")
    @Operation(
            operationId = "logOut",
            summary = "Log out",
            description = "Ends the session of the refresh cookie and clears it. Access tokens already issued stay valid"
                    + " until they expire, at most the access token lifetime.")
    @SecurityRequirement(name = SecurityConfig.REFRESH_COOKIE)
    @ApiResponse(responseCode = "204", description = "Logged out (also when there was no session)")
    public ResponseEntity<Void> logOut(
            @Parameter(hidden = true) @CookieValue(name = RefreshCookie.NAME, required = false) @Nullable String token) {
        accounts.logOut(token);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, RefreshCookie.clear().toString()).build();
    }

    static Accounts.Profile profile(Account account) {
        return new Accounts.Profile(account.id(), account.email(), account.createdAt());
    }

    private static ResponseEntity<Accounts.Session> session(HttpStatus status, AccountService.Grant grant) {
        Accounts.Session body =
                new Accounts.Session(grant.access().value(), "Bearer", grant.access().expiresIn(), profile(grant.account()));
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, RefreshCookie.issue(grant.refreshToken(), grant.refreshMaxAge()).toString())
                .body(body);
    }
}
