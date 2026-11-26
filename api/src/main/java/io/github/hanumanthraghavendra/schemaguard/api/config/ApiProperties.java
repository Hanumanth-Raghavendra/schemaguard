package io.github.hanumanthraghavendra.schemaguard.api.config;

import io.github.hanumanthraghavendra.schemaguard.api.ratelimit.Rate;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code schemaguard.*}.
 *
 * @param limits      what a single request may contain
 * @param cors        which web origins may call the API from a browser
 * @param persistence where saved analyses and accounts are kept; without a URL, nothing is saved
 *                    and accounts are switched off
 * @param auth        how logins and sessions work
 * @param projects    how much each account may keep in projects
 * @param rateLimits  how many costly requests one client address may make
 */
@Validated
@ConfigurationProperties("schemaguard")
public record ApiProperties(
        @NotNull @DefaultValue Limits limits,
        @NotNull @DefaultValue Cors cors,
        @NotNull @DefaultValue Persistence persistence,
        @NotNull @DefaultValue Auth auth,
        @NotNull @DefaultValue Projects projects,
        @NotNull @DefaultValue RateLimits rateLimits) {

    /**
     * Limits that keep one request from occupying the service. Linting costs time in
     * proportion to the size of the schema times the number of statements in the script.
     *
     * @param maxBodyBytes     the largest request body accepted, in bytes
     * @param maxInputChars    the longest single SQL input, in characters
     * @param maxStatements    the most statements a migration script to lint may have
     */
    public record Limits(
            @Min(1024) @DefaultValue("3000000") long maxBodyBytes,
            @Min(1) @DefaultValue("1000000") int maxInputChars,
            @Min(1) @DefaultValue("2000") int maxStatements) {}

    /** @param allowedOrigins origins allowed to call {@code /api/**} from a browser */
    public record Cors(@NotNull @DefaultValue({"http://localhost:5173", "http://localhost:4173"}) List<String> allowedOrigins) {}

    /**
     * Saved analyses (permalinks).
     *
     * @param url              a JDBC URL ({@code jdbc:postgresql://...}) or a libpq connection
     *                         string ({@code postgresql://user:password@host/db?sslmode=require},
     *                         as Neon shows it); empty means permalinks are switched off
     * @param username         the user, if the URL does not carry one
     * @param password         the password, if the URL does not carry one
     * @param retention        how long a saved analysis is kept
     * @param maxAnalysisBytes the largest analysis that is saved: request and result together
     * @param maxTotalBytes    the most all saved analyses may take together; beyond it, saving
     *                         is refused until old ones expire
     * @param poolSize         the most database connections held at once
     * @param connectTimeout   how long a request waits for a database connection before it
     *                         gets a 503
     */
    public record Persistence(
            String url,
            String username,
            String password,
            @NotNull @DefaultValue("30d") Duration retention,
            @Min(1024) @DefaultValue("1000000") long maxAnalysisBytes,
            @Min(1024) @DefaultValue("200000000") long maxTotalBytes,
            @Min(1) @DefaultValue("3") int poolSize,
            @NotNull @DefaultValue("10s") Duration connectTimeout) {

        /** True if a database is configured. */
        public boolean enabled() {
            return url != null && !url.isBlank();
        }

        @Override
        public String toString() {
            // Never print the connection string: it may carry a password.
            return "Persistence[enabled=" + enabled() + ", retention=" + retention + "]";
        }
    }

    /**
     * Accounts and sessions. A session is an access token (a short-lived JWT the client keeps in
     * memory and sends as {@code Authorization: Bearer}) plus a refresh token (random, stored only
     * as a hash, sent in an httpOnly cookie, exchanged for a new one on every use).
     *
     * @param jwtSecret            the key that signs access tokens (HS256), at least 32
     *                             characters; without one a random key is made at startup, so
     *                             access tokens stop working when the service restarts and
     *                             clients refresh them
     * @param accessTokenLifetime  how long an access token is accepted
     * @param refreshTokenLifetime how long a refresh token lasts unused; each use issues a new
     *                             one, so an active session slides forward
     * @param sessionLifetime      the longest a session lasts after the login, however active
     * @param refreshReuseGrace    how long after its exchange a refresh token is still accepted
     *                             once more (two tabs refreshing at the same moment); a later
     *                             reuse is treated as theft and ends the session
     */
    public record Auth(
            String jwtSecret,
            @NotNull @DefaultValue("15m") Duration accessTokenLifetime,
            @NotNull @DefaultValue("30d") Duration refreshTokenLifetime,
            @NotNull @DefaultValue("90d") Duration sessionLifetime,
            @NotNull @DefaultValue("30s") Duration refreshReuseGrace) {

        @Override
        public String toString() {
            // Never print the signing key.
            return "Auth[jwtSecret=" + (jwtSecret == null || jwtSecret.isBlank() ? "random" : "set")
                    + ", accessTokenLifetime=" + accessTokenLifetime + ", sessionLifetime=" + sessionLifetime + "]";
        }
    }

    /**
     * Projects and their schema versions. Each version is also held to {@link Limits#maxInputChars}.
     *
     * @param maxProjects     projects per account
     * @param maxVersions     versions per project
     * @param maxAccountBytes schema text one account may keep across all its versions
     * @param maxTotalBytes   schema text all accounts together may keep; beyond it, saving a
     *                        version is refused until something is deleted
     */
    public record Projects(
            @Min(1) @DefaultValue("20") int maxProjects,
            @Min(1) @DefaultValue("500") int maxVersions,
            @Min(1024) @DefaultValue("20000000") long maxAccountBytes,
            @Min(1024) @DefaultValue("200000000") long maxTotalBytes) {}
    /**
     * Limits per client address (D-117), each written {@code requests/period}, such as
     * {@code 60/1m}. Which address counts is explained in D-116.
     *
     * @param enabled          false switches all of them off
     * @param analyses         analyses run, including the one behind saving a project version
     * @param shares           analyses saved under a public link
     * @param signups          accounts created
     * @param logins           login attempts, on top of the lock after failed logins per account
     * @param connectingFactor how many times each rate one connecting address may use for all the
     *                         visitors it names together: the web app's visitors arrive through
     *                         Vercel's few addresses, and a made-up visitor address is still held
     *                         to this
     * @param maxTracked       how many address counts are kept at most; the oldest unused go first
     */
    public record RateLimits(
            @DefaultValue("true") boolean enabled,
            @NotNull @DefaultValue("60/1m") Rate analyses,
            @NotNull @DefaultValue("30/1h") Rate shares,
            @NotNull @DefaultValue("20/1h") Rate signups,
            @NotNull @DefaultValue("30/10m") Rate logins,
            @Min(1) @DefaultValue("10") int connectingFactor,
            @Min(100) @DefaultValue("100000") int maxTracked) {}
}
