package dev.webhooks.platform.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.webhooks.platform.circuit.CircuitBreaker;
import dev.webhooks.platform.circuit.CircuitBreaker.Signal;
import dev.webhooks.platform.circuit.CircuitBreaker.State;
import dev.webhooks.platform.circuit.CircuitState;
import dev.webhooks.platform.config.WebhookProperties;
import dev.webhooks.platform.health.EndpointHealth;
import dev.webhooks.platform.health.HealthStatus;
import dev.webhooks.platform.worker.AttemptOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CircuitBreakerTest {

    static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    final CircuitBreaker breaker = new CircuitBreaker(
            new WebhookProperties.Circuit(true, 3, Duration.ofSeconds(30), Duration.ofMinutes(30), 5));

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "SUCCESS, 200, SUCCESS",
            "HTTP_SERVER_ERROR, 500, FAILURE",
            "HTTP_SERVER_ERROR, 503, FAILURE",
            "TIMEOUT, null, FAILURE",
            "CONNECTION_ERROR, null, FAILURE",
            "DNS_ERROR, null, FAILURE",
            "TLS_ERROR, null, FAILURE",
            "HTTP_CLIENT_ERROR, 408, FAILURE",
            "RATE_LIMITED, 429, NEUTRAL",
            "DESTINATION_BLOCKED, null, NEUTRAL",
            "HTTP_CLIENT_ERROR, 400, REACHABLE",
            "HTTP_CLIENT_ERROR, 410, REACHABLE",
            "HTTP_SERVER_ERROR, 501, REACHABLE",
            "HTTP_REDIRECT, 302, REACHABLE",
            "INVALID_RESPONSE, 200, REACHABLE"})
    void classifiesOutcomes(AttemptOutcome outcome, Integer status, Signal expected) {
        assertThat(CircuitBreaker.signal(outcome, status)).isEqualTo(expected);
    }

    @Test
    void opensAfterThresholdConsecutiveFailures() {
        State s = State.closed();
        s = breaker.onResult(s, Signal.FAILURE, UUID.randomUUID(), NOW);
        s = breaker.onResult(s, Signal.FAILURE, UUID.randomUUID(), NOW);
        assertThat(s.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(s.consecutiveFailures()).isEqualTo(2);
        s = breaker.onResult(s, Signal.FAILURE, UUID.randomUUID(), NOW);
        assertThat(s.state()).isEqualTo(CircuitState.OPEN);
        assertThat(s.openCount()).isEqualTo(1);
        assertThat(s.openedAt()).isEqualTo(NOW);
        assertThat(s.retryAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(s.probeDeliveryId()).isNull();
    }

    @Test
    void successAndReachableAnswersEndTheStreakButNeutralDoesNot() {
        State two = new State(CircuitState.CLOSED, 2, 0, null, null, null);
        assertThat(breaker.onResult(two, Signal.SUCCESS, UUID.randomUUID(), NOW).consecutiveFailures()).isZero();
        assertThat(breaker.onResult(two, Signal.REACHABLE, UUID.randomUUID(), NOW).consecutiveFailures()).isZero();
        assertThat(breaker.onResult(two, Signal.NEUTRAL, UUID.randomUUID(), NOW)).isEqualTo(two);
    }

    @Test
    void probeSuccessOrReachableAnswerCloses() {
        UUID probe = UUID.randomUUID();
        State halfOpen = new State(CircuitState.HALF_OPEN, 3, 2, NOW, NOW, probe);
        assertThat(breaker.onResult(halfOpen, Signal.SUCCESS, probe, NOW)).isEqualTo(State.closed());
        assertThat(breaker.onResult(halfOpen, Signal.REACHABLE, probe, NOW)).isEqualTo(State.closed());
    }

    @Test
    void probeFailureReopensWithALongerCooldown() {
        UUID probe = UUID.randomUUID();
        State halfOpen = new State(CircuitState.HALF_OPEN, 3, 1, NOW.minusSeconds(30), NOW, probe);
        State s = breaker.onResult(halfOpen, Signal.FAILURE, probe, NOW);
        assertThat(s.state()).isEqualTo(CircuitState.OPEN);
        assertThat(s.openCount()).isEqualTo(2);
        assertThat(s.retryAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(s.consecutiveFailures()).isEqualTo(4);
    }

    @Test
    void probeRateLimitedLeavesTheCircuitWaitingForTheSameProbe() {
        UUID probe = UUID.randomUUID();
        State halfOpen = new State(CircuitState.HALF_OPEN, 3, 1, NOW, NOW, probe);
        assertThat(breaker.onResult(halfOpen, Signal.NEUTRAL, probe, NOW)).isEqualTo(halfOpen);
    }

    @Test
    void lateResultsFromNonProbeDeliveriesNeverChangeAnOpenOrHalfOpenState() {
        UUID probe = UUID.randomUUID();
        State open = new State(CircuitState.OPEN, 3, 1, NOW, NOW.plusSeconds(30), null);
        State s = breaker.onResult(open, Signal.FAILURE, UUID.randomUUID(), NOW);
        assertThat(s.state()).isEqualTo(CircuitState.OPEN);
        assertThat(s.retryAt()).isEqualTo(open.retryAt());
        assertThat(s.openCount()).isEqualTo(1);

        State halfOpen = new State(CircuitState.HALF_OPEN, 3, 1, NOW, NOW, probe);
        State late = breaker.onResult(halfOpen, Signal.SUCCESS, UUID.randomUUID(), NOW);
        assertThat(late.state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(late.probeDeliveryId()).isEqualTo(probe);
    }

    @Test
    void cooldownDoublesUpToTheCap() {
        assertThat(breaker.cooldown(0)).isEqualTo(Duration.ofSeconds(30));
        assertThat(breaker.cooldown(1)).isEqualTo(Duration.ofSeconds(60));
        assertThat(breaker.cooldown(5)).isEqualTo(Duration.ofMinutes(16));
        assertThat(breaker.cooldown(6)).isEqualTo(Duration.ofMinutes(30));
        assertThat(breaker.cooldown(1_000)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new WebhookProperties.Circuit(true, 0, Duration.ofSeconds(1), Duration.ofSeconds(1), 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebhookProperties.Circuit(true, 1, Duration.ofSeconds(10), Duration.ofSeconds(5), 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            // enabled, circuit, consecutive failures, attempts, success rate, dead letters -> status
            "false, OPEN, 9, 100, 0.1, 3, DISABLED",
            "true, OPEN, 0, 0, null, 0, FAILING",
            "true, HALF_OPEN, 0, 0, null, 0, FAILING",
            "true, CLOSED, 5, 5, 0.0, 0, FAILING",
            "true, CLOSED, 4, 10, 0.89, 0, DEGRADED",
            "true, CLOSED, 0, 9, 0.5, 0, HEALTHY",
            "true, CLOSED, 0, 10, 0.9, 0, HEALTHY",
            "true, CLOSED, 0, 3, 1.0, 1, DEGRADED",
            "true, CLOSED, 0, 0, null, 0, NO_DATA",
            "true, CLOSED, 0, 0, null, 1, DEGRADED"})
    void classifiesHealth(boolean enabled, CircuitState circuit, int failures, long attempts, Double rate,
            long deadLetters, HealthStatus expected) {
        assertThat(EndpointHealth.classify(enabled, circuit, failures, 5, attempts, rate, deadLetters))
                .isEqualTo(expected);
    }
}
