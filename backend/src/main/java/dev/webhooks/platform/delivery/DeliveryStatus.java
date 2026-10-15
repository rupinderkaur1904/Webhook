package dev.webhooks.platform.delivery;

/**
 * Delivery lifecycle (docs/PLAN.md §4.3).
 *
 * <pre>
 * PENDING / RETRYING --claim--> IN_FLIGHT --2xx--> DELIVERED
 *                                         --retryable, attempts left--> RETRYING
 *                                         --permanent / blocked / exhausted--> DEAD_LETTERED
 * PENDING / RETRYING --endpoint deleted--> CANCELLED
 * </pre>
 *
 * DELIVERED, DEAD_LETTERED and CANCELLED are terminal.
 */
public enum DeliveryStatus {
    PENDING,
    RETRYING,
    IN_FLIGHT,
    DELIVERED,
    DEAD_LETTERED,
    CANCELLED
}
