package dev.webhooks.platform.health;

import dev.webhooks.platform.common.TimeWindow;
import dev.webhooks.platform.config.WebhookProperties;
import dev.webhooks.platform.endpoint.Endpoint;
import dev.webhooks.platform.health.EndpointHealth.LastFailure;
import dev.webhooks.platform.health.EndpointHealthRepository.Stats;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class EndpointHealthService {

    private final EndpointHealthRepository repository;
    private final int failingAfter;

    public EndpointHealthService(EndpointHealthRepository repository, WebhookProperties properties) {
        this.repository = repository;
        this.failingAfter = properties.circuit().failingAfter();
    }

    /** Health of each given endpoint (all from one account) over {@code window}. */
    public Map<UUID, EndpointHealth> forEndpoints(UUID accountId, List<Endpoint> endpoints, TimeWindow window) {
        UUID only = endpoints.size() == 1 ? endpoints.getFirst().id() : null;
        Map<UUID, Stats> stats = endpoints.isEmpty() ? Map.of() : repository.stats(accountId, only, window.duration());
        Map<UUID, EndpointHealth> result = new HashMap<>();
        for (Endpoint endpoint : endpoints) {
            result.put(endpoint.id(), health(endpoint, stats.getOrDefault(endpoint.id(), Stats.EMPTY), window));
        }
        return result;
    }

    public EndpointHealth forEndpoint(UUID accountId, Endpoint endpoint, TimeWindow window) {
        return forEndpoints(accountId, List.of(endpoint), window).get(endpoint.id());
    }

    private EndpointHealth health(Endpoint endpoint, Stats s, TimeWindow window) {
        HealthStatus status = EndpointHealth.classify(endpoint.enabled(), endpoint.circuit().state(),
                endpoint.circuit().consecutiveFailures(), failingAfter, s.attempts(), s.successRate(), s.deadLetters());
        LastFailure lastFailure = s.lastFailureAt() == null ? null
                : new LastFailure(s.lastFailureAt(), s.lastFailureOutcome(), s.lastFailureStatus(), s.lastFailureError());
        return new EndpointHealth(status, window.name(), s.attempts(), s.failed(), s.successRate(), s.p50(), s.p95(),
                s.deadLetters(), s.lastSuccessAt(), lastFailure);
    }
}
