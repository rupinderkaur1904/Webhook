package dev.webhooks.platform.worker;

/** What happened on one HTTP attempt, independent of whether it will be retried. */
public enum AttemptOutcome {
    SUCCESS,
    HTTP_REDIRECT,
    HTTP_CLIENT_ERROR,
    RATE_LIMITED,
    HTTP_SERVER_ERROR,
    TIMEOUT,
    CONNECTION_ERROR,
    DNS_ERROR,
    TLS_ERROR,
    DESTINATION_BLOCKED,
    INVALID_RESPONSE,
    /** No result was recorded before the worker's lease expired (crash, hang, or shutdown timeout). */
    LEASE_EXPIRED
}
