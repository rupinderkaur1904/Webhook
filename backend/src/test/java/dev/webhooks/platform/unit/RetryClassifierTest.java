package dev.webhooks.platform.unit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.webhooks.platform.retry.RetryClassifier;
import dev.webhooks.platform.worker.AttemptOutcome;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RetryClassifierTest {

    @ParameterizedTest(name = "{0} {1} -> retryable={2}")
    @CsvSource(nullValues = "null", value = {
            "HTTP_REDIRECT, 301, false", "HTTP_REDIRECT, 307, false",
            "HTTP_CLIENT_ERROR, 400, false", "HTTP_CLIENT_ERROR, 401, false", "HTTP_CLIENT_ERROR, 403, false",
            "HTTP_CLIENT_ERROR, 404, false", "HTTP_CLIENT_ERROR, 410, false", "HTTP_CLIENT_ERROR, 413, false",
            "HTTP_CLIENT_ERROR, 422, false", "HTTP_CLIENT_ERROR, 408, true", "HTTP_CLIENT_ERROR, 425, true",
            "RATE_LIMITED, 429, true",
            "HTTP_SERVER_ERROR, 500, true", "HTTP_SERVER_ERROR, 502, true", "HTTP_SERVER_ERROR, 503, true",
            "HTTP_SERVER_ERROR, 504, true", "HTTP_SERVER_ERROR, 599, true",
            "HTTP_SERVER_ERROR, 501, false", "HTTP_SERVER_ERROR, 505, false",
            "TIMEOUT, null, true", "CONNECTION_ERROR, null, true", "DNS_ERROR, null, true", "TLS_ERROR, null, true",
            "DESTINATION_BLOCKED, null, false", "INVALID_RESPONSE, null, false", "INVALID_RESPONSE, 999, false"})
    void classifiesOutcomes(AttemptOutcome outcome, Integer status, boolean retryable) {
        assertThat(RetryClassifier.isRetryable(outcome, status)).isEqualTo(retryable);
    }
}
