package dev.webhooks.platform.circuit;

import dev.webhooks.platform.config.WebhookProperties;
import dev.webhooks.platform.retry.RetryClassifier;
import dev.webhooks.platform.worker.AttemptOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The per-endpoint circuit breaker's state machine (docs/PLAN.md §7.5), as pure functions so every
 * transition is unit-tested. {@link CircuitService} applies it to the endpoint row inside the same
 * transaction that records an attempt.
 *
 * <ul>
 *   <li>CLOSED: deliveries flow. {@code failureThreshold} consecutive failures open the circuit.</li>
 *   <li>OPEN: the endpoint's deliveries are not claimed (no attempts, no retry budget used) until
 *       {@code retryAt}. The cool-down doubles each time the circuit opens again, up to a cap.</li>
 *   <li>HALF_OPEN: exactly one probe delivery may be sent. Success, or any answer that proves the
 *       endpoint is reachable, closes the circuit; a failure opens it again for longer.</li>
 * </ul>
 * A failure is a retryable outcome other than 429: rate limiting is backpressure, not breakage, and a
 * non-retryable 4xx is a payload problem, not endpoint health.
 */
public final class CircuitBreaker {

    /** What an attempt says about the endpoint's health. */
    public enum Signal {
        /** 2xx. */
        SUCCESS,
        /** The endpoint answered, but with something retrying will not fix (e.g. 400): it is reachable. */
        REACHABLE,
        /** A retryable failure other than 429: counts towards opening the circuit. */
        FAILURE,
        /** Says nothing about health: 429 backpressure, or a destination blocked before sending. */
        NEUTRAL
    }

    public record State(
            CircuitState state,
            int consecutiveFailures,
            int openCount,
            Instant openedAt,
            Instant retryAt,
            UUID probeDeliveryId) {

        public static State closed() {
            return new State(CircuitState.CLOSED, 0, 0, null, null, null);
        }
    }

    private final WebhookProperties.Circuit config;

    public CircuitBreaker(WebhookProperties.Circuit config) {
        this.config = config;
    }

    public static Signal signal(AttemptOutcome outcome, Integer statusCode) {
        return switch (outcome) {
            case SUCCESS -> Signal.SUCCESS;
            case RATE_LIMITED, DESTINATION_BLOCKED -> Signal.NEUTRAL;
            default -> RetryClassifier.isRetryable(outcome, statusCode) ? Signal.FAILURE : Signal.REACHABLE;
        };
    }

    /** Cool-down for the {@code openCount}-th opening (0-based): base × 2^n, capped. */
    public Duration cooldown(int openCount) {
        Duration cooldown = config.baseCooldown();
        for (int i = 0; i < openCount && cooldown.compareTo(config.maxCooldown()) < 0; i++) {
            cooldown = cooldown.multipliedBy(2);
        }
        return cooldown.compareTo(config.maxCooldown()) > 0 ? config.maxCooldown() : cooldown;
    }

    /** The state after an attempt of {@code deliveryId} with the given signal finished at {@code now}. */
    public State onResult(State current, Signal signal, UUID deliveryId, Instant now) {
        boolean probe = current.state() == CircuitState.HALF_OPEN && deliveryId.equals(current.probeDeliveryId());
        return switch (signal) {
            // Any answer that proves the endpoint is up ends a failure streak.
            case SUCCESS, REACHABLE -> probe ? State.closed()
                    : new State(current.state(), 0, current.openCount(), current.openedAt(), current.retryAt(),
                            current.probeDeliveryId());
            case NEUTRAL -> current;
            case FAILURE -> {
                int failures = current.consecutiveFailures() + 1;
                boolean trip = probe
                        || (current.state() == CircuitState.CLOSED && failures >= config.failureThreshold());
                yield trip ? open(failures, current.openCount(), now)
                        : new State(current.state(), failures, current.openCount(), current.openedAt(),
                                current.retryAt(), current.probeDeliveryId());
            }
        };
    }

    private State open(int failures, int openCount, Instant now) {
        return new State(CircuitState.OPEN, failures, openCount + 1, now, now.plus(cooldown(openCount)), null);
    }
}
