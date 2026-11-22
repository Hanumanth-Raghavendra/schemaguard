package io.github.hanumanthraghavendra.schemaguard.api.auth;

import java.time.Duration;
import org.springframework.http.ResponseCookie;

/**
 * The cookie that carries the refresh token: httpOnly (scripts cannot read it, so cross-site
 * scripting cannot steal it), Secure, SameSite=Strict (no other site's page can make the browser
 * send it), without a Domain (only the host that set it gets it back) and only on the
 * {@value #PATH} paths. The {@code __Secure-} prefix makes the browser refuse it unless it is
 * Secure, so a plain-HTTP page cannot plant one.
 *
 * <p>The web app reaches the API through its own origin (D-090), so this is a first-party cookie
 * of the web app's host.
 */
public final class RefreshCookie {

    public static final String NAME = "__Secure-sg-refresh";
    public static final String PATH = "/api/v1/auth";

    private RefreshCookie() {}

    public static ResponseCookie issue(String token, Duration maxAge) {
        return base(token).maxAge(maxAge).build();
    }

    /** Tells the browser to forget the cookie. */
    public static ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(true).sameSite("Strict").path(PATH);
    }
}
