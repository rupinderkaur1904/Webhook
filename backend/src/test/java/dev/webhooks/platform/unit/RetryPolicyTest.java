package dev.webhooks.platform.unit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.webhooks.platform.config.WebhookProperties;
import dev.webhooks.platform.delivery.DeadLetterReason;
import dev.webhooks.platform.retry.RetryPolicy;
import dev.webhooks.platform.retry.RetryPolicy.Decision;
import dev.webhooks.platform.worker.AttemptOutcome;
import java.time.Duration;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RetryPolicyTest {

    private static final WebhookProperties.Retry CONFIG =
            new WebhookProperties.Retry(8, Duration.ofSeconds(30), Duration.ofHours(1), Duration.ofHours(6));

    private final RetryPolicy policy = new RetryPolicy(CONFIG, new SplittableRandom(42)::nextLong);

    @ParameterizedTest(name = "after attempt {0}: between {1}s and {2}s")
    @CsvSource({
            "1, 15, 30", "2, 30, 60", "3, 60, 120", "4, 120, 240", "5, 240, 480",
            "6, 480, 960", "7, 960, 1920", "8, 1800, 3600", "20, 1800, 3600", "100, 1800, 3600"})
    void backoffDoublesWithEqualJitterAndIsCapped(int attempt, long minSeconds, long maxSeconds) {
        for (int i = 0; i < 2_000; i++) {
            Duration delay = policy.backoff(attempt);
            assertThat(delay).isBetween(Duration.ofSeconds(minSeconds), Duration.ofSeconds(maxSeconds));
        }
    }

    @Test
    void jitterActuallySpreadsDelays() {
        long distinct = java.util.stream.IntStream.range(0, 200).mapToObj(i -> policy.backoff(3)).distinct().count();
        assertThat(distinct).isGreaterThan(150);
    }

    @Test
    void successDelivers() {
        assertThat(policy.decide(AttemptOutcome.SUCCESS, 200, null, 1, 8)).isInstanceOf(Decision.Deliver.class);
    }

    @Test
    void retryableFailureWithAttemptsLeftIsRetried() {
        assertThat(policy.decide(AttemptOutcome.HTTP_SERVER_ERROR, 500, null, 7, 8)).isInstanceOf(Decision.Retry.class);
    }

    @Test
    void lastAttemptExhaustsBudget() {
        assertThat(policy.decide(AttemptOutcome.TIMEOUT, null, null, 8, 8))
                .isEqualTo(new Decision.DeadLetter(DeadLetterReason.RETRIES_EXHAUSTED));
    }

    @Test
    void permanentFailureIsDeadLetteredEvenWithAttemptsLeft() {
        assertThat(policy.decide(AttemptOutcome.HTTP_CLIENT_ERROR, 400, null, 1, 8))
                .isEqualTo(new Decision.DeadLetter(DeadLetterReason.NON_RETRYABLE_RESPONSE));
        assertThat(policy.decide(AttemptOutcome.HTTP_REDIRECT, 301, null, 1, 8))
                .isEqualTo(new Decision.DeadLetter(DeadLetterReason.NON_RETRYABLE_RESPONSE));
    }

    @Test
    void blockedDestinationIsDeadLetteredWithItsOwnReason() {
        assertThat(policy.decide(AttemptOutcome.DESTINATION_BLOCKED, null, null, 1, 8))
                .isEqualTo(new Decision.DeadLetter(DeadLetterReason.DESTINATION_BLOCKED));
    }

    @Test
    void retryAfterRaisesDelayOn429And503Only() {
        Duration ten = Duration.ofMinutes(10);
        assertThat(((Decision.Retry) policy.decide(AttemptOutcome.RATE_LIMITED, 429, ten, 1, 8)).delay()).isEqualTo(ten);
        assertThat(((Decision.Retry) policy.decide(AttemptOutcome.HTTP_SERVER_ERROR, 503, ten, 1, 8)).delay()).isEqualTo(ten);
        assertThat(((Decision.Retry) policy.decide(AttemptOutcome.HTTP_SERVER_ERROR, 500, ten, 1, 8)).delay())
                .isLessThanOrEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void retryAfterShorterThanBackoffDoesNotShortenIt() {
        Duration delay = ((Decision.Retry) policy.decide(AttemptOutcome.RATE_LIMITED, 429, Duration.ofSeconds(1), 4, 8)).delay();
        assertThat(delay).isGreaterThanOrEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void retryAfterIsCapped() {
        Duration delay = ((Decision.Retry) policy.decide(AttemptOutcome.RATE_LIMITED, 429, Duration.ofDays(30), 1, 8)).delay();
        assertThat(delay).isEqualTo(Duration.ofHours(6));
    }

    @Test
    void retryAfterDoesNotBypassTheAttemptBudget() {
        assertThat(policy.decide(AttemptOutcome.RATE_LIMITED, 429, Duration.ofSeconds(5), 8, 8))
                .isEqualTo(new Decision.DeadLetter(DeadLetterReason.RETRIES_EXHAUSTED));
    }
}
