package dev.webhooks.platform.common;

import java.time.Instant;
import java.util.List;

/** One page of a keyset-paginated list. {@code nextCursor} is null on the last page. */
public record Page<T>(List<T> items, @ApiNullable String nextCursor) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public static int checkLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.validation("limit", "must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }

    private static final Instant MIN_TIME = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");

    /**
     * A {@code from}/{@code to} filter must lie in years 1-9999. Instants outside PostgreSQL's
     * timestamptz range would otherwise fail in the database and surface as a 500.
     */
    public static Instant checkTime(String name, Instant value) {
        if (value != null && (value.isBefore(MIN_TIME) || value.isAfter(MAX_TIME))) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                    "Parameter '" + name + "' must be between years 1 and 9999");
        }
        return value;
    }
}
