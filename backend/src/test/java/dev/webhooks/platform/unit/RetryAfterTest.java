package dev.webhooks.platform.unit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.webhooks.platform.retry.RetryAfter;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RetryAfterTest {

    private static final Instant NOW = Instant.parse("2026-10-15T04:00:00Z");

    @Test
    void parsesDelaySeconds() {
        assertThat(RetryAfter.parse("120", NOW)).contains(Duration.ofSeconds(120));
        assertThat(RetryAfter.parse(" 0 ", NOW)).contains(Duration.ZERO);
    }

    @Test
    void parsesHttpDate() {
        assertThat(RetryAfter.parse("Thu, 15 Oct 2026 04:02:00 GMT", NOW)).contains(Duration.ofMinutes(2));
    }

    @Test
    void pastHttpDateMeansNow() {
        assertThat(RetryAfter.parse("Thu, 15 Oct 2026 03:00:00 GMT", NOW)).contains(Duration.ZERO);
    }

    @Test
    void hugeValuesAreReturnedForTheCallerToCap() {
        assertThat(RetryAfter.parse("99999999999999999999", NOW)).hasValueSatisfying(d -> assertThat(d).isGreaterThan(Duration.ofDays(365)));
    }

    @Test
    void ignoresInvalidValues() {
        assertThat(RetryAfter.parse(null, NOW)).isEmpty();
        assertThat(RetryAfter.parse("", NOW)).isEmpty();
        assertThat(RetryAfter.parse("-5", NOW)).isEmpty();
        assertThat(RetryAfter.parse("1.5", NOW)).isEmpty();
        assertThat(RetryAfter.parse("soon", NOW)).isEmpty();
    }
}
