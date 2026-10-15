package dev.webhooks.platform.retry;

import dev.webhooks.platform.worker.AttemptOutcome;

/**
 * Decides whether a failed attempt is worth retrying (docs/PLAN.md §7.4).
 *
 * <ul>
 *   <li>Retryable: 408, 425, 429, 5xx except 501/505, timeouts, connection and DNS errors, TLS errors,
 *       and attempts abandoned because the worker's lease expired.</li>
 *   <li>Permanent: 3xx (redirects are never followed), other 4xx, 501, 505, invalid responses.</li>
 *   <li>Blocked destinations are permanent and handled separately (no request is sent).</li>
 * </ul>
 * TLS errors are retried because they are usually a receiver misconfiguration that can be fixed
 * within the retry window; the retry budget bounds the cost.
 */
public final class RetryClassifier {

    private RetryClassifier() {}

    public static boolean isRetryable(AttemptOutcome outcome, Integer statusCode) {
        return switch (outcome) {
            case SUCCESS, HTTP_REDIRECT, DESTINATION_BLOCKED, INVALID_RESPONSE -> false;
            case RATE_LIMITED, TIMEOUT, CONNECTION_ERROR, DNS_ERROR, TLS_ERROR, LEASE_EXPIRED -> true;
            case HTTP_CLIENT_ERROR -> statusCode != null && (statusCode == 408 || statusCode == 425);
            case HTTP_SERVER_ERROR -> statusCode == null || (statusCode != 501 && statusCode != 505);
        };
    }

    /** {@code Retry-After} is honoured only where it carries scheduling meaning: 429 and 503. */
    public static boolean honoursRetryAfter(Integer statusCode) {
        return statusCode != null && (statusCode == 429 || statusCode == 503);
    }
}
