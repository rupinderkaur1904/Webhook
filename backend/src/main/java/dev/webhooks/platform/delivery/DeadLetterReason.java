package dev.webhooks.platform.delivery;

public enum DeadLetterReason {
    /** The retry budget was used up by retryable failures. */
    RETRIES_EXHAUSTED,
    /** The receiver answered with a response that will not succeed on retry (e.g. 400, 404, 3xx). */
    NON_RETRYABLE_RESPONSE,
    /** The destination resolved to an address the platform refuses to contact. */
    DESTINATION_BLOCKED
}
