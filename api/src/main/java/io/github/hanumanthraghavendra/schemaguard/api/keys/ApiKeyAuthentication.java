package io.github.hanumanthraghavendra.schemaguard.api.keys;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** A request authenticated with an API key. It holds the {@code KEY} authority only. */
public final class ApiKeyAuthentication extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    /** What a request may do when it came with an API key; a login gives {@code SESSION}. */
    public static final String AUTHORITY = "KEY";

    private final ApiKeyPrincipal principal;

    public ApiKeyAuthentication(ApiKeyPrincipal principal) {
        super(List.of(new SimpleGrantedAuthority(AUTHORITY)));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public ApiKeyPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public Object getCredentials() {
        // The key itself is not kept once it is checked.
        return "";
    }
}
