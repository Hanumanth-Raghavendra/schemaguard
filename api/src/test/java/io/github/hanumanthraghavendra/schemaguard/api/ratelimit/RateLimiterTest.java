package io.github.hanumanthraghavendra.schemaguard.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.TimeMeter;
import io.github.hanumanthraghavendra.schemaguard.api.config.ApiProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The buckets on a clock moved by hand. */
class RateLimiterTest {

    /** A clock that moves only when told. */
    private static final class Clock implements TimeMeter {
        private long nanos;

        void advance(Duration duration) {
            nanos += duration.toNanos();
        }

        @Override
        public long currentTimeNanos() {
            return nanos;
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
    }

    private final Clock clock = new Clock();
    private final RateLimiter limiter =
            new RateLimiter(
                    new ApiProperties.RateLimits(
                            true, Rate.valueOf("3/1m"), Rate.valueOf("2/1h"), Rate.valueOf("2/1h"), Rate.valueOf("5/10m"), 2, 1000),
                    clock);

    private static ClientAddress direct(String address) {
        return new ClientAddress(address, address);
    }

    @Test
    void aVisitorGetsTheirRateThenWaits() {
        ClientAddress caller = direct("203.0.113.7");
        assertThat(limiter.take(Category.ANALYSES, caller).remaining()).isEqualTo(2);
        assertThat(limiter.take(Category.ANALYSES, caller).remaining()).isEqualTo(1);
        RateLimiter.Decision third = limiter.take(Category.ANALYSES, caller);
        assertThat(third.allowed()).isTrue();
        assertThat(third.remaining()).isZero();
        assertThat(third.limit()).isEqualTo(Rate.valueOf("3/1m"));
        assertThat(third.retryAfter()).isZero();

        RateLimiter.Decision fourth = limiter.take(Category.ANALYSES, caller);
        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.exceeded()).isEqualTo(Rate.valueOf("3/1m"));
        assertThat(fourth.remaining()).isZero();
        // 3 a minute refill one every 20 seconds.
        assertThat(fourth.retryAfter()).isEqualTo(Duration.ofSeconds(20));
        assertThat(fourth.retryAfterSeconds()).isEqualTo(20);

        clock.advance(Duration.ofSeconds(19));
        RateLimiter.Decision early = limiter.take(Category.ANALYSES, caller);
        assertThat(early.allowed()).isFalse();
        assertThat(early.retryAfterSeconds()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.take(Category.ANALYSES, caller).allowed()).isTrue();
        assertThat(limiter.take(Category.ANALYSES, caller).allowed()).isFalse();
    }

    @Test
    void retryAfterRoundsUpToAWholeSecond() {
        assertThat(new RateLimiter.Decision(Rate.valueOf("1/1m"), 0, Rate.valueOf("1/1m"), Duration.ofMillis(1)).retryAfterSeconds())
                .isEqualTo(1);
        assertThat(new RateLimiter.Decision(Rate.valueOf("1/1m"), 0, Rate.valueOf("1/1m"), Duration.ofMillis(1001)).retryAfterSeconds())
                .isEqualTo(2);
        assertThat(new RateLimiter.Decision(Rate.valueOf("1/1m"), 0, null, Duration.ZERO).retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void addressesAndCategoriesCountSeparately() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.take(Category.ANALYSES, direct("203.0.113.7")).allowed()).isTrue();
        }
        assertThat(limiter.take(Category.ANALYSES, direct("203.0.113.7")).allowed()).isFalse();
        assertThat(limiter.take(Category.ANALYSES, direct("203.0.113.8")).allowed()).isTrue();
        assertThat(limiter.take(Category.LOGINS, direct("203.0.113.7")).allowed()).isTrue();
    }

    @Test
    void theSameVisitorCountsOnceWhetherTheyComeDirectOrThroughVercel() {
        ClientAddress throughVercel = new ClientAddress("198.51.100.23", "3.80.12.34");
        ClientAddress direct = direct("198.51.100.23");
        assertThat(limiter.take(Category.SHARES, throughVercel).remaining()).isEqualTo(1);
        assertThat(limiter.take(Category.SHARES, direct).remaining()).isZero();
        assertThat(limiter.take(Category.SHARES, new ClientAddress("198.51.100.23", "3.80.99.1")).allowed()).isFalse();
    }

    @Test
    void madeUpVisitorsFromOneAddressAreHeldToTheFactorTimesTheRate() {
        // Rate 3, factor 2: one connecting address may carry 6 analyses a minute for all its visitors.
        int allowed = 0;
        for (int i = 0; i < 20; i++) {
            if (limiter.take(Category.ANALYSES, new ClientAddress("192.0.2." + i, "203.0.113.7")).allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(6);
        RateLimiter.Decision refused = limiter.take(Category.ANALYSES, new ClientAddress("192.0.2.100", "203.0.113.7"));
        assertThat(refused.exceeded()).isEqualTo(Rate.valueOf("6/1m"));
        assertThat(refused.limit()).isEqualTo(Rate.valueOf("3/1m"));
        assertThat(refused.retryAfter()).isEqualTo(Duration.ofSeconds(10));
        // Calling directly counts against the same connecting address.
        assertThat(limiter.take(Category.ANALYSES, direct("203.0.113.7")).allowed()).isFalse();
    }

    @Test
    void aRequestTheConnectingCapRefusesIsNotCountedAgainstTheVisitor() {
        ClientAddress visitor = new ClientAddress("198.51.100.23", "3.80.12.34");
        for (int i = 0; i < 6; i++) {
            assertThat(limiter.take(Category.ANALYSES, new ClientAddress("192.0.2." + i, "3.80.12.34")).allowed()).isTrue();
        }
        assertThat(limiter.take(Category.ANALYSES, visitor).allowed()).isFalse();
        // Through another of Vercel's addresses the visitor still has all three.
        ClientAddress elsewhere = new ClientAddress("198.51.100.23", "3.80.12.35");
        assertThat(limiter.take(Category.ANALYSES, elsewhere).remaining()).isEqualTo(2);
    }

    @Test
    void bucketsAreBoundedByMaxTracked() {
        RateLimiter small =
                new RateLimiter(
                        new ApiProperties.RateLimits(
                                true, Rate.valueOf("3/1m"), Rate.valueOf("2/1h"), Rate.valueOf("2/1h"), Rate.valueOf("5/10m"), 2, 100),
                        clock);
        for (int i = 0; i < 500; i++) {
            small.take(Category.ANALYSES, direct("10.0." + (i / 256) + "." + (i % 256)));
        }
        assertThat(small.tracked()).isLessThanOrEqualTo(100);
    }
}
