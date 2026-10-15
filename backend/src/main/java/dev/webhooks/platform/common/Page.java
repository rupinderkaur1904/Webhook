package dev.webhooks.platform.common;

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
}
