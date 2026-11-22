package io.github.hanumanthraghavendra.schemaguard.api.auth;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A refresh token as it is stored: only its hash.
 *
 * @param family           the login it belongs to; every token exchanged from it shares the family
 * @param expiresAt        when it stops working if unused
 * @param sessionExpiresAt when the whole family stops working, however active
 * @param usedAt           when it was exchanged for a new token
 * @param revokedAt        when its session was ended (logout, theft detected)
 */
public record RefreshToken(
        String hash,
        UUID accountId,
        UUID family,
        Instant issuedAt,
        Instant expiresAt,
        Instant sessionExpiresAt,
        @Nullable Instant usedAt,
        @Nullable Instant revokedAt) {}
