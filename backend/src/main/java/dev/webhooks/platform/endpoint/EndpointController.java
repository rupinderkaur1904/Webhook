package dev.webhooks.platform.endpoint;

import dev.webhooks.platform.auth.AccountPrincipal;
import dev.webhooks.platform.endpoint.EndpointDtos.CreateEndpointRequest;
import dev.webhooks.platform.endpoint.EndpointDtos.EndpointCreatedResponse;
import dev.webhooks.platform.endpoint.EndpointDtos.EndpointListResponse;
import dev.webhooks.platform.endpoint.EndpointDtos.EndpointResponse;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/endpoints")
public class EndpointController {

    private final EndpointService endpoints;
    private final IngestionService ingestion;

    public EndpointController(EndpointService endpoints, IngestionService ingestion) {
        this.endpoints = endpoints;
        this.ingestion = ingestion;
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
        return EndpointResponse.from(endpoints.get(principal.accountId(), id));
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

    /** Sends a real {@code webhook.test} event through the normal delivery pipeline to this endpoint. */
    @PostMapping("/{id}/test")
    ResponseEntity<EventAcceptedResponse> sendTest(@AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ingestion.sendTestEvent(principal.accountId(), id));
    }
}
