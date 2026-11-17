package io.github.hanumanthraghavendra.schemaguard.api.config;

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
 * @param persistence where saved analyses are kept; without a URL, nothing is saved
 */
@Validated
@ConfigurationProperties("schemaguard")
public record ApiProperties(
        @NotNull @DefaultValue Limits limits,
        @NotNull @DefaultValue Cors cors,
        @NotNull @DefaultValue Persistence persistence) {

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
    public record Cors(@NotNull @DefaultValue("http://localhost:5173") List<String> allowedOrigins) {}

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
}
