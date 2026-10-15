package dev.webhooks.platform.unit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.webhooks.platform.config.WebhookProperties;
import dev.webhooks.platform.config.WebhookProperties.Delivery;
import dev.webhooks.platform.config.WebhookProperties.Worker;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WebhookPropertiesTest {

    static Worker worker(Duration lease) {
        return new Worker(true, Duration.ofMillis(500), 32, 32, lease, Duration.ofSeconds(20), Duration.ofSeconds(15), 100);
    }

    static WebhookProperties properties(Duration lease, Duration connect, Duration response) {
        return new WebhookProperties(null, null, worker(lease), new Delivery(connect, response, 2048), null, null, null);
    }

    @Test
    void leaseMustOutlastTheSlowestPossibleAttempt() {
        assertThatThrownBy(() -> properties(Duration.ofSeconds(19), Duration.ofSeconds(5), Duration.ofSeconds(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease-duration");
        assertThatCode(() -> properties(Duration.ofSeconds(25), Duration.ofSeconds(5), Duration.ofSeconds(15)))
                .doesNotThrowAnyException();
        assertThatCode(() -> properties(Duration.ofSeconds(60), Duration.ofSeconds(5), Duration.ofSeconds(15)))
                .doesNotThrowAnyException();
    }
}
