package dev.webhooks.platform.delivery;

import dev.webhooks.platform.common.ApiNullable;
import io.swagger.v3.oas.annotations.media.Schema;
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
            @ApiNullable Integer lastResponseStatus,
            @ApiNullable String lastError,
            @ApiNullable Instant lastAttemptAt,
            @ApiNullable Instant nextAttemptAt,
            @ApiNullable DeadLetterReason deadLetterReason,
            @ApiNullable Instant deliveredAt,
            @ApiNullable Instant deadLetteredAt,
            @ApiNullable UUID replayOfDeliveryId,
            UUID rootDeliveryId,
            Instant createdAt) {}

    public record AttemptView(
            UUID id,
            int attemptNumber,
            @Schema(allowableValues = {"STARTED", "SUCCEEDED", "FAILED", "ABANDONED"}) String status,
            @ApiNullable String outcome,
            @ApiNullable Boolean retryable,
            String requestUrl,
            @ApiNullable Integer responseStatus,
            Map<String, String> responseHeaders,
            @ApiNullable String responseBodyExcerpt,
            @ApiNullable String errorMessage,
            @ApiNullable Integer retryAfterSeconds,
            Instant startedAt,
            @ApiNullable Instant finishedAt,
            @ApiNullable Integer durationMs,
            boolean reportedAfterLeaseExpiry) {}

    /**
     * Whether the delivery can be replayed now. {@code reason} is one of
     * {@link ReplayService.IneligibleReason} when it cannot.
     */
    public record ReplayEligibility(boolean eligible,
            @ApiNullable @Schema(allowableValues = {"NOT_DEAD_LETTERED", "ENDPOINT_DELETED", "ENDPOINT_DISABLED",
                    "REPLAY_IN_PROGRESS", "REPLAY_LIMIT_REACHED"}) String reason, int replayCount, int maxReplays) {}

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
            @ApiNullable Lease lease,
            JsonNode eventBody,
            List<AttemptView> attempts,
            ReplayEligibility replayEligibility,
            List<DeliverySummary> chain) {}
}
