package dev.webhooks.platform.retry;

import dev.webhooks.platform.config.WebhookProperties;
import dev.webhooks.platform.delivery.DeadLetterReason;
import dev.webhooks.platform.worker.AttemptOutcome;
import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Turns the outcome of attempt {@code n} into the delivery's next state.
 *
 * <p>Backoff: {@code raw = min(maxDelay, baseDelay * 2^(n-1))}, then equal jitter
 * {@code delay = raw/2 + random(0, raw/2)}. Equal jitter spreads retries so receivers recovering
 * from an outage are not hit in synchronized waves, while guaranteeing at least half the intended
 * backoff. A valid {@code Retry-After} on 429/503 raises the delay to at least the requested value,
 * capped at {@code maxRetryAfter} so a receiver cannot park a delivery indefinitely.
 */
public class RetryPolicy {

    public sealed interface Decision {
        record Deliver() implements Decision {}

        record Retry(Duration delay) implements Decision {}

        record DeadLetter(DeadLetterReason reason) implements Decision {}
    }

    private final WebhookProperties.Retry config;
    private final RandomGenerator random;

    public RetryPolicy(WebhookProperties.Retry config, RandomGenerator random) {
        this.config = config;
        this.random = random;
    }

    public int maxAttempts() {
        return config.maxAttempts();
    }

    public Decision decide(AttemptOutcome outcome, Integer statusCode, Duration retryAfter, int attemptNumber,
            int maxAttempts) {
        if (outcome == AttemptOutcome.SUCCESS) {
            return new Decision.Deliver();
        }
        if (outcome == AttemptOutcome.DESTINATION_BLOCKED) {
            return new Decision.DeadLetter(DeadLetterReason.DESTINATION_BLOCKED);
        }
        if (!RetryClassifier.isRetryable(outcome, statusCode)) {
            return new Decision.DeadLetter(DeadLetterReason.NON_RETRYABLE_RESPONSE);
        }
        if (attemptNumber >= maxAttempts) {
            return new Decision.DeadLetter(DeadLetterReason.RETRIES_EXHAUSTED);
        }
        Duration delay = backoff(attemptNumber);
        if (retryAfter != null && RetryClassifier.honoursRetryAfter(statusCode)) {
            Duration requested = retryAfter.compareTo(config.maxRetryAfter()) > 0 ? config.maxRetryAfter() : retryAfter;
            if (requested.compareTo(delay) > 0) {
                delay = requested;
            }
        }
        return new Decision.Retry(delay);
    }

    /** Jittered delay before the attempt that follows attempt {@code attemptNumber} (1-based). */
    public Duration backoff(int attemptNumber) {
        long rawMillis = rawBackoffMillis(attemptNumber);
        long half = rawMillis / 2;
        return Duration.ofMillis(half + random.nextLong(rawMillis - half + 1));
    }

    long rawBackoffMillis(int attemptNumber) {
        long base = config.baseDelay().toMillis();
        long max = config.maxDelay().toMillis();
        int exponent = Math.max(0, attemptNumber - 1);
        // Stop doubling once past the cap; avoids overflow for large attempt numbers.
        if (exponent >= 62 || base > (max >> Math.min(exponent, 62))) {
            return max;
        }
        return Math.min(max, base << exponent);
    }
}
