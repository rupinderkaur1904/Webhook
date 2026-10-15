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
            Integer durationMs) {}

    public record DeliveryDetail(
            DeliverySummary delivery,
            String endpointUrl,
            JsonNode eventBody,
            List<AttemptView> attempts) {}
}
