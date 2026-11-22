package io.github.hanumanthraghavendra.schemaguard.api.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An account as it is stored.
 *
 * @param email        lowercased
 * @param passwordHash Argon2id, in the PHC string format ({@code $argon2id$v=19$m=...})
 * @param failedLogins failed logins in a row within the counting window
 * @param lockedUntil  until when logins are refused after too many failures
 */
public record Account(
        UUID id, String email, String passwordHash, Instant createdAt, int failedLogins, @Nullable Instant lockedUntil) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    @Override
    public String toString() {
        // Neither the email nor the hash belongs in a log line.
        return "Account[" + id + "]";
    }
}
