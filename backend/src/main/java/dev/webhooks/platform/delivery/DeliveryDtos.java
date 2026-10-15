package dev.webhooks.platform.delivery;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class DeliveryDtos {

    private DeliveryDtos() {}

    public record DeliverySummary(
            UUID id,
            UUID eventId,
            UUID endpointId,
            String endpointName,
            String eventType,
            DeliveryStatus status,
            int attemptCount,
            int maxAttempts,
            Integer lastResponseStatus,
            String lastError,
            Instant lastAttemptAt,
            Instant nextAttemptAt,
            DeadLetterReason deadLetterReason,
            Instant deliveredAt,
            Instant deadLetteredAt,
            UUID replayOfDeliveryId,
            UUID rootDeliveryId,
            Instant createdAt) {}

    public record AttemptView(
            UUID id,
            int attemptNumber,
            String status,
            String outcome,
            Boolean retryable,
            String requestUrl,
            Integer responseStatus,
            Map<String, String> responseHeaders,
            String responseBodyExcerpt,
            String errorMessage,
            Integer retryAfterSeconds,
            Instant startedAt,
            Instant finishedAt,
            Integer durationMs,
            boolean reportedAfterLeaseExpiry) {}

    /**
     * Whether the delivery can be replayed now. {@code reason} is one of
     * {@link ReplayService.IneligibleReason} when it cannot.
     */
    public record ReplayEligibility(boolean eligible, String reason, int replayCount, int maxReplays) {}

    /**
     * {@code chain} lists every delivery of this event to this endpoint, oldest first: the original
     * and each replay, including this one.
     */
    /** The worker currently holding an in-flight delivery, and when its lease runs out. */
    public record Lease(String owner, Instant expiresAt) {}

    /**
     * {@code lease} is set only while the delivery is in flight.
     */
    public record DeliveryDetail(
            DeliverySummary delivery,
            String endpointUrl,
            Lease lease,
            JsonNode eventBody,
            List<AttemptView> attempts,
            ReplayEligibility replayEligibility,
            List<DeliverySummary> chain) {}
}
