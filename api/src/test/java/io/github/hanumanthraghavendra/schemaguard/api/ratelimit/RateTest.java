package io.github.hanumanthraghavendra.schemaguard.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RateTest {

    @Test
    void readsRequestsAndAPeriod() {
        assertThat(Rate.valueOf("60/1m")).isEqualTo(new Rate(60, Duration.ofMinutes(1)));
        assertThat(Rate.valueOf(" 30 / 10m ")).isEqualTo(new Rate(30, Duration.ofMinutes(10)));
        assertThat(Rate.valueOf("20/1h")).isEqualTo(new Rate(20, Duration.ofHours(1)));
        assertThat(Rate.valueOf("5/PT30S")).isEqualTo(new Rate(5, Duration.ofSeconds(30)));
    }

    @Test
    void refusesWhatIsNotARate() {
        for (String text : new String[] {"60", "/1m", "60/", "sixty/1m", "60/soon"}) {
            assertThatThrownBy(() -> Rate.valueOf(text)).as(text).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> Rate.valueOf("0/1m")).hasMessageContaining("at least one request");
        assertThatThrownBy(() -> Rate.valueOf("5/500ms")).hasMessageContaining("at least one second");
        assertThatThrownBy(() -> new Rate(5, null)).hasMessageContaining("at least one second");
    }

    @Test
    void describesItselfForPeople() {
        assertThat(Rate.valueOf("60/1m").describe()).isEqualTo("60 per minute");
        assertThat(Rate.valueOf("30/10m").describe()).isEqualTo("30 per 10 minutes");
        assertThat(Rate.valueOf("20/1h").describe()).isEqualTo("20 per hour");
        assertThat(Rate.valueOf("200/2h").describe()).isEqualTo("200 per 2 hours");
        assertThat(Rate.valueOf("5/90s").describe()).isEqualTo("5 per 90 seconds");
        assertThat(Rate.valueOf("1/1s").describe()).isEqualTo("1 per second");
        assertThat(Rate.valueOf("60/1m")).hasToString("60/PT1M");
    }

    @Test
    void multipliesForTheConnectingCap() {
        assertThat(Rate.valueOf("60/1m").times(10)).isEqualTo(new Rate(600, Duration.ofMinutes(1)));
        assertThatThrownBy(() -> new Rate(Long.MAX_VALUE, Duration.ofMinutes(1)).times(2)).isInstanceOf(ArithmeticException.class);
    }
}
