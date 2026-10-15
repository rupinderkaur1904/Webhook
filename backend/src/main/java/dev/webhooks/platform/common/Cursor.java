package dev.webhooks.platform.common;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Opaque keyset-pagination cursor over {@code (created_at DESC, id DESC)}. Clients must treat the
 * encoded value as opaque.
 */
public record Cursor(Instant createdAt, UUID id) {

    public String encode() {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, createdAt);
        String raw = micros + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int sep = raw.indexOf(':');
            long micros = Long.parseLong(raw.substring(0, sep));
            return new Cursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), UUID.fromString(raw.substring(sep + 1)));
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "The pagination cursor is invalid");
        }
    }
}
