package dev.webhooks.platform.ratelimit;

import dev.webhooks.platform.common.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** 429 with a {@code Retry-After} header (seconds). */
public class RateLimitedException extends ApiException {

    private final long retryAfterSeconds;

    public RateLimitedException(String detail, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", detail, List.of(), Map.of("retryAfterSeconds", retryAfterSeconds));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
