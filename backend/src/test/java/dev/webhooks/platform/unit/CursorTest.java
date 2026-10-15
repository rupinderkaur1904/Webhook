package dev.webhooks.platform.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.webhooks.platform.common.ApiException;
import dev.webhooks.platform.common.Cursor;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CursorTest {

    @Test
    void roundTripsWithMicrosecondPrecision() {
        Cursor cursor = new Cursor(Instant.parse("2026-10-15T04:25:47.123456Z"), UUID.randomUUID());
        assertThat(Cursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> Cursor.decode("not-a-cursor")).isInstanceOf(ApiException.class);
    }
}
