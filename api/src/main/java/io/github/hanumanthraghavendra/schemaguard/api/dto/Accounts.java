package io.github.hanumanthraghavendra.schemaguard.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Accounts and sessions: what sign-up, login, refresh and the account endpoint return. */
public final class Accounts {

    private Accounts() {}

    @Schema(name = "Account", description = "A SchemaGuard account")
    public record Profile(UUID id, @Schema(example = "you@example.com") String email, Instant createdAt) {}

    @Schema(
            name = "Session",
            description = "A logged-in session. The refresh token travels separately, in an httpOnly cookie the browser"
                    + " keeps and sends to /api/v1/auth only")
    public record Session(
            @Schema(description = "Send as Authorization: Bearer <accessToken>. A JWT (RFC 9068); keep it in memory only")
                    String accessToken,
            @Schema(example = "Bearer", allowableValues = {"Bearer"}) String tokenType,
            @Schema(description = "Seconds until the access token expires; then call /api/v1/auth/refresh", example = "900")
                    long expiresIn,
            Profile account) {

        @Override
        public String toString() {
            return "Session[" + account.id() + "]";
        }
    }
}
