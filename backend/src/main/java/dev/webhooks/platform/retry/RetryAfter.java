package dev.webhooks.platform.retry;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/** Parses an HTTP {@code Retry-After} value: delay-seconds or an HTTP-date (RFC 9110 §10.2.3). */
public final class RetryAfter {

    private RetryAfter() {}

    public static Optional<Duration> parse(String value, Instant now) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            if (trimmed.length() > 9) {
                return Optional.of(Duration.ofSeconds(999_999_999L)); // absurdly large; caller caps it
            }
            return Optional.of(Duration.ofSeconds(Long.parseLong(trimmed)));
        }
        try {
            Instant at = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delay = Duration.between(now, at);
            return Optional.of(delay.isNegative() ? Duration.ZERO : delay);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
