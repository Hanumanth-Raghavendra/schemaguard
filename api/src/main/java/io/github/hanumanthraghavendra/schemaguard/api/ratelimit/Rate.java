package io.github.hanumanthraghavendra.schemaguard.api.ratelimit;

import java.time.Duration;
import org.springframework.boot.convert.DurationStyle;

/**
 * How many requests one address may make in a period, written {@code 60/1m} in the settings.
 * The bucket refills gradually: {@code 60/1m} allows a burst of 60, then one more each second.
 *
 * @param requests how many
 * @param period   in how long
 */
public record Rate(long requests, Duration period) {

    public Rate {
        if (requests < 1) {
            throw new IllegalArgumentException("A rate allows at least one request, not " + requests);
        }
        if (period == null || period.isNegative() || period.toSeconds() < 1) {
            throw new IllegalArgumentException("A rate's period is at least one second, not " + period);
        }
    }

    /**
     * Reads {@code requests/period}, such as {@code 60/1m} or {@code 30/10m}. Found by Spring's
     * conversion service, which is how the settings bind to this type.
     */
    public static Rate valueOf(String text) {
        int slash = text.indexOf('/');
        if (slash < 1 || slash == text.length() - 1) {
            throw new IllegalArgumentException("Write a rate as requests/period, such as 60/1m, not '" + text + "'");
        }
        long requests;
        try {
            requests = Long.parseLong(text.substring(0, slash).strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Write a rate as requests/period, such as 60/1m, not '" + text + "'", e);
        }
        return new Rate(requests, DurationStyle.detectAndParse(text.substring(slash + 1).strip()));
    }

    /** The same period with {@code factor} times the requests. */
    public Rate times(int factor) {
        return new Rate(Math.multiplyExact(requests, factor), period);
    }

    /** For messages: {@code 60 per minute}, {@code 30 per 10 minutes}. */
    public String describe() {
        return requests + " per " + span(period);
    }

    /** For messages: {@code 1 second}, {@code 3 minutes}, {@code 1 hour}, without "1" for a single unit. */
    private static String span(Duration period) {
        long seconds = period.toSeconds();
        if (seconds % 3600 == 0) {
            return count(seconds / 3600, "hour");
        }
        if (seconds % 60 == 0) {
            return count(seconds / 60, "minute");
        }
        return count(seconds, "second");
    }

    private static String count(long n, String unit) {
        return n == 1 ? unit : n + " " + unit + "s";
    }

    @Override
    public String toString() {
        return requests + "/" + period;
    }
}
