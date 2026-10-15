package dev.webhooks.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.webhooks.simulator.ReceiverRegistry.Behaviour;
import dev.webhooks.simulator.ReceiverRegistry.Receiver;
import dev.webhooks.simulator.ReceiverRegistry.Response;
import dev.webhooks.simulator.ReceiverRegistry.Step;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReceiverBehaviourTest {

    @Test
    void scriptIsConsumedInOrderThenDefaultApplies() {
        Receiver receiver = new Receiver();
        receiver.configure(new Behaviour(200, 0L, null, Map.of(),
                List.of(new Step(500, 0L, null), new Step(429, 0L, Map.of("Retry-After", "5"))), null, null, null));

        Response first = receiver.nextResponse();
        Response second = receiver.nextResponse();
        Response third = receiver.nextResponse();

        assertThat(first.status()).isEqualTo(500);
        assertThat(first.rule()).isEqualTo("script 1/2");
        assertThat(second.status()).isEqualTo(429);
        assertThat(second.headers()).containsEntry("Retry-After", "5");
        assertThat(third.status()).isEqualTo(200);
        assertThat(third.rule()).isEqualTo("default");
    }

    @Test
    void reconfiguringRestartsTheScript() {
        Receiver receiver = new Receiver();
        Behaviour behaviour = new Behaviour(200, 0L, null, null, List.of(new Step(503, 0L, null)), null, null, null);
        receiver.configure(behaviour);
        receiver.nextResponse();
        receiver.configure(behaviour);

        assertThat(receiver.nextResponse().status()).isEqualTo(503);
    }

    @Test
    void seededIntermittentFailuresAreReproducibleAndRoughlyAtTheConfiguredRate() {
        Behaviour behaviour = new Behaviour(200, 0L, null, null, null, 0.3, 502, 42L);
        List<Integer> run1 = statuses(behaviour, 1000);
        List<Integer> run2 = statuses(behaviour, 1000);

        assertThat(run1).isEqualTo(run2);
        long failures = run1.stream().filter(s -> s == 502).count();
        assertThat(failures).isBetween(240L, 360L);
        assertThat(run1).containsOnly(200, 502);
    }

    @Test
    void zeroFailureRateNeverFails() {
        assertThat(statuses(new Behaviour(204, 0L, null, null, null, 0.0, null, 1L), 200)).containsOnly(204);
    }

    @Test
    void validationRejectsBadScriptsAndRates() {
        assertThat(new Behaviour(200, 0L, null, null, List.of(new Step(42, 0L, null)), null, null, null).validate())
                .isNotNull();
        assertThat(new Behaviour(200, 0L, null, null, null, 1.5, null, null).validate()).isNotNull();
        assertThat(new Behaviour(200, 0L, null, null, null, 0.5, 99, null).validate()).isNotNull();
        assertThat(new Behaviour(200, ReceiverRegistry.MAX_DELAY_MS + 1, null, null, null, null, null, null).validate())
                .isNotNull();
        assertThat(new Behaviour(200, 20_000L, null, null, List.of(new Step(500, 0L, null)), 0.1, 503, 7L).validate())
                .isNull();
    }

    private static List<Integer> statuses(Behaviour behaviour, int n) {
        Receiver receiver = new Receiver();
        receiver.configure(behaviour);
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            statuses.add(receiver.nextResponse().status());
        }
        return statuses;
    }
}
