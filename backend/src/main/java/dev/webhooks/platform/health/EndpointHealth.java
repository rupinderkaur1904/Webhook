package dev.webhooks.platform.health;

import dev.webhooks.platform.common.ApiNullable;
import io.swagger.v3.oas.annotations.media.Schema;
import dev.webhooks.platform.circuit.CircuitState;
import java.time.Instant;

/**
 * Health numbers for one endpoint over a window, from {@code delivery_attempts} and the endpoint's
 * circuit. Attempts count only finished ones; abandoned attempts count as failed, as in the overview.
 */
public record EndpointHealth(
        HealthStatus status,
        @Schema(allowableValues = {"1h", "24h", "7d"}) String window,
        long attempts,
        long failedAttempts,
        @ApiNullable Double successRate,
        @ApiNullable Integer latencyP50Ms,
        @ApiNullable Integer latencyP95Ms,
        long deadLetters,
        @ApiNullable Instant lastSuccessAt,
        @ApiNullable LastFailure lastFailure) {

    /** The most recent failed attempt (any time, not only in the window). */
    public record LastFailure(Instant at, @ApiNullable String outcome, @ApiNullable Integer responseStatus, @ApiNullable String error) {}

    static final int DEGRADED_MIN_ATTEMPTS = 10;
    static final double DEGRADED_SUCCESS_RATE = 0.9;

    public static HealthStatus classify(boolean enabled, CircuitState circuit, int consecutiveFailures,
            int failingAfter, long attempts, Double successRate, long deadLetters) {
        if (!enabled) {
            return HealthStatus.DISABLED;
        }
        if (circuit != CircuitState.CLOSED || consecutiveFailures >= failingAfter) {
            return HealthStatus.FAILING;
        }
        if ((attempts >= DEGRADED_MIN_ATTEMPTS && successRate != null && successRate < DEGRADED_SUCCESS_RATE)
                || deadLetters > 0) {
            return HealthStatus.DEGRADED;
        }
        return attempts > 0 ? HealthStatus.HEALTHY : HealthStatus.NO_DATA;
    }
}
