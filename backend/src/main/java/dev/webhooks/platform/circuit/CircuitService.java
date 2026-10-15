package dev.webhooks.platform.circuit;

import dev.webhooks.platform.circuit.CircuitBreaker.Signal;
import dev.webhooks.platform.circuit.CircuitBreaker.State;
import dev.webhooks.platform.common.ApiException;
import dev.webhooks.platform.config.WebhookProperties;
import dev.webhooks.platform.worker.AttemptOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CircuitService {

    private static final Logger log = LoggerFactory.getLogger(CircuitService.class);

    private final CircuitRepository circuits;
    private final CircuitBreaker breaker;
    private final boolean enabled;
    private final MeterRegistry meters;
    private final Clock clock;

    public CircuitService(CircuitRepository circuits, WebhookProperties properties, MeterRegistry meters) {
        this.circuits = circuits;
        this.breaker = new CircuitBreaker(properties.circuit());
        this.enabled = properties.circuit().enabled();
        this.meters = meters;
        this.clock = Clock.systemUTC();
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Applies an attempt's result to its endpoint's circuit. Must run inside the transaction that
     * records the attempt, so the counters can never disagree with the attempt history.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordResult(UUID endpointId, UUID deliveryId, AttemptOutcome outcome, Integer statusCode) {
        if (!enabled) {
            return;
        }
        Signal signal = CircuitBreaker.signal(outcome, statusCode);
        if (signal == Signal.NEUTRAL) {
            return;
        }
        State current = circuits.lock(endpointId).orElse(null);
        if (current == null) {
            return;
        }
        State next = breaker.onResult(current, signal, deliveryId, Instant.now(clock));
        if (next.equals(current)) {
            return;
        }
        circuits.save(endpointId, next);
        if (next.state() != current.state()) {
            transitioned(endpointId, current.state(), next.state());
            if (next.state() == CircuitState.OPEN) {
                log.warn("Circuit for endpoint {} opened after {} consecutive failures; deliveries paused until {}",
                        endpointId, next.consecutiveFailures(), next.retryAt());
            } else {
                log.info("Circuit for endpoint {} {} -> {} (probe delivery {})", endpointId, current.state(),
                        next.state(), deliveryId);
            }
        }
    }

    /** OPEN circuits past their cool-down get one probe delivery. Called by the maintenance loop. */
    @Transactional
    public int startProbes(int limit) {
        if (!enabled) {
            return 0;
        }
        List<CircuitRepository.Probe> probes = circuits.startProbes(limit, Instant.now(clock));
        for (CircuitRepository.Probe probe : probes) {
            if (probe.previous() == CircuitState.OPEN) {
                transitioned(probe.endpointId(), CircuitState.OPEN, CircuitState.HALF_OPEN);
            }
            log.info("Circuit for endpoint {} half-open: delivery {} is the probe", probe.endpointId(), probe.deliveryId());
        }
        return probes.size();
    }

    @Transactional
    public void reset(UUID accountId, UUID endpointId) {
        if (!circuits.reset(accountId, endpointId)) {
            throw ApiException.notFound("Endpoint");
        }
        meters.counter("webhooks.circuit.transitions", "from", "manual_reset", "to", "CLOSED").increment();
        log.info("Circuit for endpoint {} reset by hand", endpointId);
    }

    private void transitioned(UUID endpointId, CircuitState from, CircuitState to) {
        meters.counter("webhooks.circuit.transitions", "from", from.name(), "to", to.name()).increment();
    }
}
