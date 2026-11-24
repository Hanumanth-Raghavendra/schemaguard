package io.github.hanumanthraghavendra.schemaguard.api.auth;

import io.github.hanumanthraghavendra.schemaguard.api.keys.ApiKeyPrincipal;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/** The account a request acts for, whether it came with an access token or an API key. */
public final class CurrentAccount {

    private CurrentAccount() {}

    public static UUID id(Authentication authentication) {
        return switch (authentication.getPrincipal()) {
            case Jwt jwt -> UUID.fromString(jwt.getSubject());
            case ApiKeyPrincipal key -> key.accountId();
            case null, default -> throw new IllegalStateException("Not an authenticated account: " + authentication.getClass().getSimpleName());
        };
    }
}
