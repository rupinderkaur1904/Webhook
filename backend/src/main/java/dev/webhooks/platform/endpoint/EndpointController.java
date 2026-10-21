package dev.webhooks.platform.endpoint;

import dev.webhooks.platform.auth.AccountPrincipal;
import dev.webhooks.platform.circuit.CircuitService;
import dev.webhooks.platform.common.TimeWindow;
import dev.webhooks.platform.health.EndpointHealth;
import dev.webhooks.platform.endpoint.EndpointDtos.CreateEndpointRequest;
import dev.webhooks.platform.endpoint.EndpointDtos.EndpointCreatedResponse;
import dev.webhooks.platform.endpoint.EndpointDtos.EndpointListResponse;
import dev.webhooks.platform.endpoint.EndpointDtos.EndpointResponse;
import dev.webhooks.platform.endpoint.EndpointDtos.RotateSecretRequest;
import dev.webhooks.platform.endpoint.EndpointDtos.RotateSecretResponse;
import dev.webhooks.platform.endpoint.EndpointDtos.UpdateEndpointRequest;
import dev.webhooks.platform.event.EventDtos.EventAcceptedResponse;
import dev.webhooks.platform.event.IngestionService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/endpoints")
public class EndpointController {

    private final EndpointService endpoints;
    private final IngestionService ingestion;
    private final CircuitService circuits;

    public EndpointController(EndpointService endpoints, IngestionService ingestion, CircuitService circuits) {
        this.endpoints = endpoints;
        this.ingestion = ingestion;
        this.circuits = circuits;
    }

    @PostMapping
    ResponseEntity<EndpointCreatedResponse> create(@AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody CreateEndpointRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(endpoints.create(principal.accountId(), request));
    }

    @GetMapping
    EndpointListResponse list(@AuthenticationPrincipal AccountPrincipal principal) {
        return new EndpointListResponse(endpoints.list(principal.accountId()));
    }

    @GetMapping("/{id}")
    EndpointResponse get(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id) {
        return endpoints.response(principal.accountId(), endpoints.get(principal.accountId(), id));
    }

    @PatchMapping("/{id}")
    EndpointResponse update(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody UpdateEndpointRequest request) {
        return endpoints.update(principal.accountId(), id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id) {
        endpoints.delete(principal.accountId(), id);
        return ResponseEntity.noContent().build();
    }

    /** Health over {@code window} (1h, 24h or 7d): status, attempts, success rate, latency, last success and failure. */
    @GetMapping("/{id}/health")
    EndpointHealth health(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id,
            @RequestParam(defaultValue = "24h") String window) {
        return endpoints.health(principal.accountId(), id, TimeWindow.parse(window));
    }

    /** Closes the endpoint's circuit by hand, so its queued deliveries are sent again right away. */
    @PostMapping("/{id}/circuit/reset")
    EndpointResponse resetCircuit(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id) {
        circuits.reset(principal.accountId(), id);
        return endpoints.response(principal.accountId(), endpoints.get(principal.accountId(), id));
    }

    @PostMapping("/{id}/rotate-secret")
    RotateSecretResponse rotateSecret(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody(required = false) RotateSecretRequest request) {
        return endpoints.rotateSecret(principal.accountId(), id, request);
    }

    /** Sends a real {@code webhook.test} event through the normal delivery pipeline to this endpoint. */
    @PostMapping("/{id}/test")
    ResponseEntity<EventAcceptedResponse> sendTest(@AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ingestion.sendTestEvent(principal.accountId(), id));
    }
}
