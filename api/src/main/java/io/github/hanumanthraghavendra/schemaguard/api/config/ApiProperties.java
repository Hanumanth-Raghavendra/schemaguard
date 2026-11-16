package io.github.hanumanthraghavendra.schemaguard.api.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code schemaguard.*}.
 *
 * @param limits what a single request may contain
 * @param cors   which web origins may call the API from a browser
 */
@Validated
@ConfigurationProperties("schemaguard")
public record ApiProperties(@NotNull @DefaultValue Limits limits, @NotNull @DefaultValue Cors cors) {

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
}
