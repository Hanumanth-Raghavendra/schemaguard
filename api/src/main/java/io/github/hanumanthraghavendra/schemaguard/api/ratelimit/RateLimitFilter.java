package io.github.hanumanthraghavendra.schemaguard.api.ratelimit;

import io.github.bucket4j.TimeMeter;
import io.github.hanumanthraghavendra.schemaguard.api.config.ApiProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits analyses, saved analyses, sign-ups and logins per client address (D-117). It runs
 * first, before the body is read, so a refused request costs almost nothing. Limited endpoints
 * answer with {@code X-RateLimit-Limit} and {@code X-RateLimit-Remaining}; a refused request
 * gets 429 with {@code Retry-After} in seconds and a problem document.
 */
@Component
@Order(RateLimitFilter.ORDER)
public class RateLimitFilter extends OncePerRequestFilter {

    /** Before {@link io.github.hanumanthraghavendra.schemaguard.api.config.BodySizeFilter}. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE;

    static final String LIMIT = "X-RateLimit-Limit";
    static final String REMAINING = "X-RateLimit-Remaining";

    private final boolean enabled;
    private final RateLimiter limiter;

    public RateLimitFilter(ApiProperties properties) {
        this.enabled = properties.rateLimits().enabled();
        this.limiter = new RateLimiter(properties.rateLimits(), TimeMeter.SYSTEM_NANOTIME);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || Category.of(request.getMethod(), path(request)) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Category category = Objects.requireNonNull(Category.of(request.getMethod(), path(request)));
        RateLimiter.Decision decision = limiter.take(category, ClientAddress.of(request));
        response.setHeader(LIMIT, Long.toString(decision.limit().requests()));
        response.setHeader(REMAINING, Long.toString(decision.remaining()));
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter()
                .write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\""
                        + detail(category, decision) + "\",\"code\":\"SG-API-429\"}");
    }

    /** For example: "Too many analyses from your address: at most 60 per minute. Try again in 4 seconds." */
    static String detail(Category category, RateLimiter.Decision decision) {
        return "Too many " + category.noun() + " from your address: at most "
                + Objects.requireNonNull(decision.exceeded()).describe() + ". Try again in " + inWords(decision.retryAfterSeconds()) + ".";
    }

    private static String inWords(long seconds) {
        if (seconds < 120) {
            return seconds == 1 ? "1 second" : seconds + " seconds";
        }
        return ((seconds + 59) / 60) + " minutes";
    }

    /**
     * The path the servlet container decoded and normalised, the one Spring MVC matches, so that
     * {@code /api/v1/%64iff} counts as {@code /api/v1/diff}.
     */
    static String path(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return request.getServletPath() + (pathInfo == null ? "" : pathInfo);
    }
}
