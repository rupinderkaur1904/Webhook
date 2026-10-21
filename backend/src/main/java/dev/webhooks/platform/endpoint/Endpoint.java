package dev.webhooks.platform.endpoint;

import dev.webhooks.platform.circuit.CircuitView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An endpoint row, without secret material. {@code previousSecretExpiresAt} is set only while a rotated-out secret still signs. */
public record Endpoint(
        UUID id,
        UUID accountId,
        String name,
        String description,
        String url,
        List<String> eventTypes,
        boolean enabled,
        String secretPrefix,
        Instant previousSecretExpiresAt,
        CircuitView circuit,
        Instant createdAt,
        Instant updatedAt) {}
