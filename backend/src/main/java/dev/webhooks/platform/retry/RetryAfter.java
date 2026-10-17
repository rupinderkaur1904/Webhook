package dev.webhooks.platform.retry;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Parses an HTTP {@code Retry-After} value: delay-seconds or an HTTP-date (RFC 9110 §10.2.3). All
 * three HTTP-date forms are accepted, as RFC 9110 §5.6.7 requires of recipients: IMF-fixdate and the
 * obsolete RFC 850 and asctime forms.
 */
public final class RetryAfter {

    /** RFC 850: {@code Sunday, 06-Nov-94 08:49:37 GMT}; a two-digit year within the last 50 years or the next 49. */
    private static final DateTimeFormatter RFC_850 = new DateTimeFormatterBuilder()
            .appendPattern("EEEE, dd-MMM-")
            .appendValueReduced(ChronoField.YEAR, 2, 2, LocalDate.now(ZoneOffset.UTC).getYear() - 50)
            .appendPattern(" HH:mm:ss 'GMT'")
            .toFormatter(Locale.US)
            .withZone(ZoneOffset.UTC);

    /** asctime: {@code Sun Nov  6 08:49:37 1994} (day padded with a space), always UTC. */
    private static final DateTimeFormatter ASCTIME =
            DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.US).withZone(ZoneOffset.UTC);

    private static final List<DateTimeFormatter> HTTP_DATES =
            List.of(DateTimeFormatter.RFC_1123_DATE_TIME, RFC_850, ASCTIME);

    private RetryAfter() {}

    public static Optional<Duration> parse(String value, Instant now) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        // ASCII digits only: Character.isDigit would also accept other scripts' digits.
        if (trimmed.chars().allMatch(c -> c >= '0' && c <= '9')) {
            if (trimmed.length() > 9) {
                return Optional.of(Duration.ofSeconds(999_999_999L)); // absurdly large; caller caps it
            }
            return Optional.of(Duration.ofSeconds(Long.parseLong(trimmed)));
        }
        for (DateTimeFormatter format : HTTP_DATES) {
            try {
                Instant at = ZonedDateTime.parse(trimmed, format).toInstant();
                Duration delay = Duration.between(now, at);
                return Optional.of(delay.isNegative() ? Duration.ZERO : delay);
            } catch (DateTimeParseException e) {
                // try the next form
            }
        }
        return Optional.empty();
    }
}
