package dev.webhooks.platform.event;

import dev.webhooks.platform.common.ApiNullable;
import dev.webhooks.platform.delivery.DeliveryDtos.DeliverySummary;
import dev.webhooks.platform.delivery.DeliveryStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class EventDtos {

    public static final String EVENT_TYPE_PATTERN = "^[a-z0-9]+([._-][a-z0-9]+)*$";

    private EventDtos() {}

    public record CreateEventRequest(
            @NotBlank @Size(max = 100)
            @Pattern(regexp = EVENT_TYPE_PATTERN, message = "must look like order.created (lowercase letters, digits, '.', '_' or '-')")
            String eventType,
            @NotNull JsonNode payload,
            @Size(max = 50) List<@NotNull UUID> endpointIds,
            OffsetDateTime occurredAt) {}

    public record AcceptedDelivery(UUID id, UUID endpointId, DeliveryStatus status) {}

    public record EventAcceptedResponse(
            UUID id, String eventType, String idempotencyKey, Instant createdAt, List<AcceptedDelivery> deliveries) {}

    /** {@code activeCount} counts deliveries still pending, retrying or in flight. */
    public record EventSummary(UUID id, String eventType, String idempotencyKey, Instant createdAt, int deliveryCount,
            int deliveredCount, int deadLetteredCount, int activeCount) {}

    public record EventDetail(
            UUID id,
            String eventType,
            String idempotencyKey,
            Instant createdAt,
            @ApiNullable Instant occurredAt,
            JsonNode body,
            List<DeliverySummary> deliveries) {}
}
