package dev.webhooks.platform.worker;

import java.util.Map;

/**
 * The observable result of one HTTP attempt. {@code errorMessage} is a fixed, categorized message;
 * raw exception text is never stored because it can contain internal addresses or other details.
 */
public record HttpAttemptResult(
        AttemptOutcome outcome,
        Integer statusCode,
        Map<String, String> responseHeaders,
        String responseBodyExcerpt,
        String errorMessage,
        long durationMs) {

    static HttpAttemptResult failure(AttemptOutcome outcome, String message, long durationMs) {
        return new HttpAttemptResult(outcome, null, Map.of(), null, message, durationMs);
    }
}
