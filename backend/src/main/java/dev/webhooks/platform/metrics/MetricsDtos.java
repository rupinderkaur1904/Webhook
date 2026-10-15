package dev.webhooks.platform.metrics;

import dev.webhooks.platform.common.ApiNullable;
import io.swagger.v3.oas.annotations.media.Schema;
import dev.webhooks.platform.circuit.CircuitView;
import dev.webhooks.platform.health.EndpointHealth;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MetricsDtos {

    private MetricsDtos() {}

    /**
     * Account-wide numbers for the dashboard overview. Windowed counts cover
     * {@code [windowStart, windowEnd)}; {@code current} counts are a snapshot, not windowed. Rates are
     * {@code null} when nothing in the window could count towards them.
     */
    public record Overview(
            @Schema(allowableValues = {"1h", "24h", "7d"}) String window,
            Instant windowStart,
            Instant windowEnd,
            long eventsSubmitted,
            long deliveriesSucceeded,
            DeadLettered deliveriesDeadLettered,
            Current current,
            @ApiNullable Double deliverySuccessRate,
            Attempts attempts,
            List<RecentAttempt> recentActivity,
            List<EndpointHealthItem> endpointHealth) {

        public Overview withEndpointHealth(List<EndpointHealthItem> items) {
            return new Overview(window, windowStart, windowEnd, eventsSubmitted, deliveriesSucceeded,
                    deliveriesDeadLettered, current, deliverySuccessRate, attempts, recentActivity, items);
        }
    }

    /** One endpoint's health over the overview window, worst first (FAILING, DEGRADED, ...). */
    public record EndpointHealthItem(UUID endpointId, String name, CircuitView circuit, EndpointHealth health) {}

    /** Deliveries dead-lettered in the window, total and per reason. */
    public record DeadLettered(long total, Map<String, Long> byReason) {}

    public record Current(long pending, long retrying, long inFlight) {}

    /**
     * Attempts started in the window that have finished. {@code failed} includes abandoned attempts.
     * Latency percentiles cover finished attempts that have a duration.
     */
    public record Attempts(long succeeded, long failed, @ApiNullable Double successRate, @ApiNullable Integer latencyP50Ms,
            @ApiNullable Integer latencyP95Ms) {}

    public record RecentAttempt(
            UUID attemptId,
            UUID deliveryId,
            UUID endpointId,
            String endpointName,
            String eventType,
            int attemptNumber,
            @Schema(allowableValues = {"STARTED", "SUCCEEDED", "FAILED", "ABANDONED"}) String status,
            @ApiNullable String outcome,
            @ApiNullable Integer responseStatus,
            @ApiNullable Integer durationMs,
            Instant startedAt) {}
}
