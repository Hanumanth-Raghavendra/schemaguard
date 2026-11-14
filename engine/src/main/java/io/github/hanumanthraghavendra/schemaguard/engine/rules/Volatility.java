package io.github.hanumanthraghavendra.schemaguard.engine.rules;

import io.github.hanumanthraghavendra.schemaguard.engine.model.Expression;
import io.github.hanumanthraghavendra.schemaguard.engine.parser.Expressions;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether an expression gives the same value for every row of one statement.
 *
 * <p>It matters for {@code ADD COLUMN ... DEFAULT}. PostgreSQL evaluates a default that is not
 * volatile once and stores it in the catalog, so the table is not touched. A volatile default,
 * such as {@code random()} or {@code gen_random_uuid()}, has to be evaluated for each row, and
 * that means writing the whole table again.
 *
 * <p>The volatility of the built-in functions is read from a list that
 * {@code scripts/measure-locks.sh} takes from the {@code pg_proc} catalog of a running server.
 */
public enum Volatility {
    /** Calls no function, or only built-in functions that are stable or immutable. */
    STABLE,
    /** Calls at least one function known to be volatile. */
    VOLATILE,
    /** Calls a function that is not built in, so its volatility cannot be known from here. */
    UNKNOWN;

    private static final String RESOURCE = "pg16-function-volatility.tsv";

    /** Volatile functions from widely used extensions, which the built-in list cannot contain. */
    private static final Set<String> EXTENSION_VOLATILE =
            Set.of("uuid_generate_v1", "uuid_generate_v1mc", "uuid_generate_v4", "gen_random_bytes", "gen_salt");

    private static final Map<String, Boolean> BUILT_IN = load();

    public static Volatility of(Expression expression) {
        List<String> functions = Expressions.functionCalls(expression);
        if (functions.stream().anyMatch(Volatility::isVolatile)) {
            return VOLATILE;
        }
        return functions.stream().allMatch(BUILT_IN::containsKey) ? STABLE : UNKNOWN;
    }

    private static boolean isVolatile(String function) {
        return EXTENSION_VOLATILE.contains(function) || BUILT_IN.getOrDefault(function, false);
    }

    /** Reads lines of {@code name|v} and {@code name|s} into a map from name to "is volatile". */
    private static Map<String, Boolean> load() {
        Map<String, Boolean> functions = new HashMap<>();
        try (InputStream in = Volatility.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + RESOURCE);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.startsWith("#") && !line.isBlank()) {
                    int bar = line.lastIndexOf('|');
                    functions.put(line.substring(0, bar), line.substring(bar + 1).equals("v"));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.copyOf(functions);
    }
}
