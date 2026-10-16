package dev.webhooks.platform.unit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.webhooks.platform.config.WebhookProperties;
import dev.webhooks.platform.delivery.DeadLetterReason;
import dev.webhooks.platform.retry.RetryAfter;
import dev.webhooks.platform.retry.RetryPolicy;
import dev.webhooks.platform.retry.RetryPolicy.Decision;
import dev.webhooks.platform.worker.AttemptOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Audit: Retry-After parsing (RFC 9110 §10.2.3, §5.6.7) and its interaction with the retry policy. */
class AuditRetryAfterTest {

    private static final Instant NOW = Instant.parse("2026-10-15T04:00:00Z");

    @Test
    void acceptsAllThreeHttpDateFormats() {
        // RFC 9110 §5.6.7: a recipient MUST accept IMF-fixdate and the obsolete RFC 850 and asctime forms.
        assertThat(RetryAfter.parse("Thu, 15 Oct 2026 04:02:00 GMT", NOW)).contains(Duration.ofMinutes(2));
        assertThat(RetryAfter.parse("Thursday, 15-Oct-26 04:02:00 GMT", NOW)).contains(Duration.ofMinutes(2));
        assertThat(RetryAfter.parse("Thu Oct 15 04:02:00 2026", NOW)).contains(Duration.ofMinutes(2));
        assertThat(RetryAfter.parse("Fri Oct 16 04:00:00 2026", NOW)).contains(Duration.ofDays(1));
    }

    @Test
    void rejectsMalformedValues() {
        for (String value : new String[] {"+5", "5s", "0x10", "1e3", "Thu, 15 Oct 2026", "tomorrow", "٣"}) {
            assertThat(RetryAfter.parse(value, NOW)).as(value).isEmpty();
        }
    }

    @Test
    void zeroNeverShortensTheBackoff() {
        RetryPolicy policy = new RetryPolicy(new WebhookProperties.Retry(8, Duration.ofSeconds(30), Duration.ofHours(1),
                Duration.ofHours(6)), new Random(1));
        Decision d = policy.decide(AttemptOutcome.RATE_LIMITED, 429, Duration.ZERO, 1, 8);
        assertThat(((Decision.Retry) d).delay()).isBetween(Duration.ofSeconds(15), Duration.ofSeconds(30));
    }

    @Test
    void hugeRetryAfterIsCappedAndNeverExtendsTheBudget() {
        RetryPolicy policy = new RetryPolicy(new WebhookProperties.Retry(3, Duration.ofSeconds(10), Duration.ofSeconds(60),
                Duration.ofSeconds(300)), new Random(1));
        Duration huge = RetryAfter.parse("99999999999", NOW).orElseThrow();
        assertThat(policy.decide(AttemptOutcome.HTTP_SERVER_ERROR, 503, huge, 1, 3))
                .isEqualTo(new Decision.Retry(Duration.ofSeconds(300)));
        assertThat(policy.decide(AttemptOutcome.HTTP_SERVER_ERROR, 503, huge, 3, 3))
                .isEqualTo(new Decision.DeadLetter(DeadLetterReason.RETRIES_EXHAUSTED));
    }
}
