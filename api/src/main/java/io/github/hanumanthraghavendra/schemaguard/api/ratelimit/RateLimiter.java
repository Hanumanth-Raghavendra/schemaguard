package io.github.hanumanthraghavendra.schemaguard.api.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.github.hanumanthraghavendra.schemaguard.api.config.ApiProperties;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import org.jspecify.annotations.Nullable;

/**
 * Counts requests per category in token buckets (Bucket4j), one per address, held in memory:
 * the service runs as a single instance, and a restart forgetting the counts is harmless.
 *
 * <p>Each request takes a token from two buckets. The visitor's holds the configured rate. The
 * connecting address's holds that rate times {@code connecting-factor}, and every request that
 * arrives from that address takes from it, whatever visitor it names. A caller who sends a
 * made-up {@code X-Vercel-Forwarded-For} straight to Render gets a fresh visitor for each made-up
 * value but always the same connecting address, so they are held to the factor times the rate.
 * The web app's visitors share Vercel's connecting addresses, which change from request to
 * request, so the factor gives them room (D-117).
 *
 * <p>Buckets are kept for as long as the longest period after their last use; by then a bucket
 * is full again, so dropping it changes nothing. At most {@code max-tracked} are kept, which
 * bounds the memory an attacker can make the service use.
 */
public final class RateLimiter {

    private final ApiProperties.RateLimits limits;
    private final TimeMeter clock;
    private final Cache<String, Bucket> buckets;

    /**
     * @param limits the settings
     * @param clock  the time source of the buckets; tests move it by hand
     */
    public RateLimiter(ApiProperties.RateLimits limits, TimeMeter clock) {
        this.limits = limits;
        this.clock = clock;
        Duration longest = Arrays.stream(Category.values())
                .map(category -> category.rate(limits).period())
                .max(Comparator.naturalOrder())
                .orElseThrow();
        this.buckets = Caffeine.newBuilder().maximumSize(limits.maxTracked()).expireAfterAccess(longest).build();
    }

    /** Takes one request of the category from the address's counts, if both have room. */
    public Decision take(Category category, ClientAddress address) {
        Rate rate = category.rate(limits);
        Bucket visitor = bucket("visitor", category, address.visitor(), rate);
        ConsumptionProbe own = visitor.tryConsumeAndReturnRemaining(1);
        if (!own.isConsumed()) {
            return Decision.refused(rate, rate, own);
        }
        Rate cap = rate.times(limits.connectingFactor());
        ConsumptionProbe shared = bucket("connecting", category, address.connecting(), cap).tryConsumeAndReturnRemaining(1);
        if (!shared.isConsumed()) {
            // The request is refused, so it does not count against the visitor either.
            visitor.addTokens(1);
            return Decision.refused(rate, cap, shared);
        }
        return new Decision(rate, own.getRemainingTokens(), null, Duration.ZERO);
    }

    private Bucket bucket(String role, Category category, String address, Rate rate) {
        return buckets.get(
                role + ' ' + category + ' ' + address,
                key -> Bucket.builder()
                        .addLimit(Bandwidth.builder().capacity(rate.requests()).refillGreedy(rate.requests(), rate.period()).build())
                        .withCustomTimePrecision(clock)
                        .build());
    }

    /** How many buckets are held; for tests. */
    long tracked() {
        buckets.cleanUp();
        return buckets.estimatedSize();
    }

    /**
     * The answer for one request.
     *
     * @param limit      the visitor's rate, reported in {@code X-RateLimit-Limit}
     * @param remaining  requests the visitor has left right now; 0 when refused
     * @param exceeded   the rate that refused the request: the visitor's, or the connecting
     *                   address's cap; null when the request may go ahead
     * @param retryAfter how long until a request would be accepted; zero when it may go ahead
     */
    public record Decision(Rate limit, long remaining, @Nullable Rate exceeded, Duration retryAfter) {

        static Decision refused(Rate limit, Rate exceeded, ConsumptionProbe probe) {
            return new Decision(limit, 0, exceeded, Duration.ofNanos(probe.getNanosToWaitForRefill()));
        }

        public boolean allowed() {
            return exceeded == null;
        }

        /** {@link #retryAfter()} in whole seconds, rounded up and at least 1, for {@code Retry-After}. */
        public long retryAfterSeconds() {
            return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
        }
    }
}
