package io.github.hanumanthraghavendra.schemaguard.api.ratelimit;

import io.github.hanumanthraghavendra.schemaguard.api.config.ApiProperties;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The kinds of request that are limited, each with its own count. The rest are cheap, or
 * already bounded per account (projects, keys), or need a session.
 */
public enum Category {

    /** Running an analysis, the one thing that costs real CPU; saving a version runs one too. */
    ANALYSES("analyses"),
    /** Saving an analysis under a public link: it takes space in the database. */
    SHARES("saved analyses"),
    SIGNUPS("sign-ups"),
    /** Per address, on top of the per-account lock after failed logins (D-094). */
    LOGINS("login attempts");

    private static final Set<String> ANALYSIS_PATHS =
            Set.of("/api/v1/diff", "/api/v1/model", "/api/v1/report", "/api/v1/plan", "/api/v1/lint");
    private static final Set<String> SHARE_PATHS =
            Set.of("/api/v1/analyses/diff", "/api/v1/analyses/report", "/api/v1/analyses/plan", "/api/v1/analyses/lint");
    private static final Pattern NEW_VERSION = Pattern.compile("/api/v1/projects/[^/]+/versions");

    private final String noun;

    Category(String noun) {
        this.noun = noun;
    }

    /** For messages: "Too many analyses". */
    public String noun() {
        return noun;
    }

    public Rate rate(ApiProperties.RateLimits limits) {
        return switch (this) {
            case ANALYSES -> limits.analyses();
            case SHARES -> limits.shares();
            case SIGNUPS -> limits.signups();
            case LOGINS -> limits.logins();
        };
    }

    /** The category of a request, by method and decoded path; null if it is not limited. */
    public static @Nullable Category of(String method, String path) {
        if (!"POST".equals(method)) {
            return null;
        }
        if (ANALYSIS_PATHS.contains(path) || NEW_VERSION.matcher(path).matches()) {
            return ANALYSES;
        }
        if (SHARE_PATHS.contains(path)) {
            return SHARES;
        }
        return switch (path) {
            case "/api/v1/auth/signup" -> SIGNUPS;
            case "/api/v1/auth/login" -> LOGINS;
            default -> null;
        };
    }
}
