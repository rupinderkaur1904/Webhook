package dev.webhooks.platform.metrics;

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
            String window,
            Instant windowStart,
            Instant windowEnd,
            long eventsSubmitted,
            long deliveriesSucceeded,
            DeadLettered deliveriesDeadLettered,
            Current current,
            Double deliverySuccessRate,
            Attempts attempts,
            List<RecentAttempt> recentActivity) {}

    /** Deliveries dead-lettered in the window, total and per reason. */
    public record DeadLettered(long total, Map<String, Long> byReason) {}

    public record Current(long pending, long retrying, long inFlight) {}

    /**
     * Attempts started in the window that have finished. {@code failed} includes abandoned attempts.
     * Latency percentiles cover finished attempts that have a duration.
     */
    public record Attempts(long succeeded, long failed, Double successRate, Integer latencyP50Ms,
            Integer latencyP95Ms) {}

    public record RecentAttempt(
            UUID attemptId,
            UUID deliveryId,
            UUID endpointId,
            String endpointName,
            String eventType,
            int attemptNumber,
            String status,
            String outcome,
            Integer responseStatus,
            Integer durationMs,
            Instant startedAt) {}
}
