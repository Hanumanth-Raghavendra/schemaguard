package io.github.hanumanthraghavendra.schemaguard.api.keys;

import io.github.hanumanthraghavendra.schemaguard.api.persistence.PersistenceUnavailableException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

/** Checks a bearer token that is an API key against the stored hashes. */
public class ApiKeyAuthenticationProvider implements AuthenticationProvider {

    private final ApiKeyService keys;

    public ApiKeyAuthenticationProvider(ApiKeyService keys) {
        this.keys = keys;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String token = ((BearerTokenAuthenticationToken) authentication).getToken();
        try {
            ApiKey key = keys.authenticate(token)
                    .orElseThrow(() -> new InvalidBearerTokenException("The API key is unknown or was deleted."));
            return new ApiKeyAuthentication(new ApiKeyPrincipal(key.accountId(), key.id(), key.name()));
        } catch (PersistenceUnavailableException e) {
            // Not the caller's fault: answered 503, not 401.
            throw new AuthenticationServiceException(e.getMessage(), e);
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return BearerTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
