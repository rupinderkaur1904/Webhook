package dev.webhooks.platform.endpoint;

import dev.webhooks.platform.common.ApiNullable;
import dev.webhooks.platform.circuit.CircuitView;
import dev.webhooks.platform.health.EndpointHealth;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class EndpointDtos {

    /** An exact event type, or a prefix wildcard such as {@code order.*}. */
    static final String EVENT_TYPE_FILTER = "^[a-z0-9]+([._-][a-z0-9]+)*(\\.\\*)?$";

    private EndpointDtos() {}

    public record CreateEndpointRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @NotBlank @Size(max = 2048) String url,
            @Size(max = 50) List<@NotNull @Pattern(regexp = EVENT_TYPE_FILTER,
                    message = "must be an event type such as order.created, or a prefix such as order.*") String> eventTypes) {}

    /** Partial update: omitted (null) fields are left unchanged. */
    public record UpdateEndpointRequest(
            @Size(min = 1, max = 100) String name,
            @Size(max = 500) String description,
            @Size(min = 1, max = 2048) String url,
            @Size(max = 50) List<@NotNull @Pattern(regexp = EVENT_TYPE_FILTER,
                    message = "must be an event type such as order.created, or a prefix such as order.*") String> eventTypes,
            Boolean enabled) {}

    public record EndpointResponse(
            UUID id,
            String name,
            @ApiNullable String description,
            String url,
            List<String> eventTypes,
            boolean enabled,
            String secretPrefix,
            @ApiNullable Instant previousSecretExpiresAt,
            CircuitView circuit,
            /** Health over the last 24 hours (docs/PLAN.md §7.5). */
            EndpointHealth health,
            Instant createdAt,
            Instant updatedAt) {

        static EndpointResponse from(Endpoint e, EndpointHealth health) {
            return new EndpointResponse(e.id(), e.name(), e.description(), e.url(), e.eventTypes(), e.enabled(),
                    e.secretPrefix(), e.previousSecretExpiresAt(), e.circuit(), health, e.createdAt(), e.updatedAt());
        }
    }

    /** Returned only on creation: the signing secret is never shown again. */
    public record EndpointCreatedResponse(EndpointResponse endpoint, String secret) {}

    /** Hours the old secret keeps signing alongside the new one; 0 drops it immediately. Default 24. */
    public record RotateSecretRequest(@Min(0) @Max(72) Integer gracePeriodHours) {}

    /** Returned only on rotation: the new secret is never shown again. */
    public record RotateSecretResponse(String secret, String secretPrefix, @ApiNullable Instant previousSecretExpiresAt) {}

    public record EndpointListResponse(List<EndpointResponse> items) {}

}
