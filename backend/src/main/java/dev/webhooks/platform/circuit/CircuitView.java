package dev.webhooks.platform.circuit;

import dev.webhooks.platform.common.ApiNullable;
import java.time.Instant;

/**
 * An endpoint's circuit as the API shows it. {@code retryAt} is when an OPEN circuit lets a probe
 * through; {@code openedAt} when it last opened.
 */
public record CircuitView(CircuitState state, int consecutiveFailures, @ApiNullable Instant openedAt, @ApiNullable Instant retryAt) {

    public static CircuitView closed() {
        return new CircuitView(CircuitState.CLOSED, 0, null, null);
    }
}
