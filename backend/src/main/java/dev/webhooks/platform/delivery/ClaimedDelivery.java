package dev.webhooks.platform.delivery;

import java.util.UUID;

/**
 * A delivery claimed by a worker. {@code leaseToken} fences the result: the result is only applied
 * if the row still carries this token, so a worker whose lease expired cannot overwrite newer state.
 */
public record ClaimedDelivery(
        UUID deliveryId,
        UUID accountId,
        UUID eventId,
        UUID endpointId,
        UUID attemptId,
        int attemptNumber,
        int maxAttempts,
        UUID leaseToken,
        String requestUrl) {}
