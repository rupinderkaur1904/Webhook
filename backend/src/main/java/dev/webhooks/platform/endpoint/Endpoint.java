package dev.webhooks.platform.endpoint;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An endpoint row, without secret material. */
public record Endpoint(
        UUID id,
        UUID accountId,
        String name,
        String description,
        String url,
        List<String> eventTypes,
        boolean enabled,
        String secretPrefix,
        Instant createdAt,
        Instant updatedAt) {}
