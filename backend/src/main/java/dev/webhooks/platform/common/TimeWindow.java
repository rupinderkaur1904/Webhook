package dev.webhooks.platform.common;

import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** The reporting windows the API accepts: {@code 1h}, {@code 24h} and {@code 7d}. */
public record TimeWindow(String name, Duration duration) {

    private static final Map<String, Duration> WINDOWS =
            Map.of("1h", Duration.ofHours(1), "24h", Duration.ofHours(24), "7d", Duration.ofDays(7));

    public static final TimeWindow DAY = new TimeWindow("24h", Duration.ofHours(24));

    public static TimeWindow parse(String name) {
        Duration duration = WINDOWS.get(name);
        if (duration == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "window must be one of 1h, 24h, 7d");
        }
        return new TimeWindow(name, duration);
    }
}
