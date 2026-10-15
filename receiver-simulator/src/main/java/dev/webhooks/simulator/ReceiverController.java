package dev.webhooks.simulator;

import dev.webhooks.simulator.ReceiverRegistry.Behaviour;
import dev.webhooks.simulator.ReceiverRegistry.ReceivedRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Webhook target: {@code POST /receivers/{name}}. Control API under the same path:
 * {@code PUT .../config}, {@code GET .../requests}, {@code DELETE /receivers/{name}}.
 */
@RestController
public class ReceiverController {

    private final ReceiverRegistry registry;

    public ReceiverController(ReceiverRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/receivers/{name}")
    ResponseEntity<Map<String, Object>> receive(@PathVariable String name, HttpServletRequest request,
            @RequestBody(required = false) byte[] body) throws InterruptedException {
        var receiver = registry.get(name);
        Behaviour behaviour = receiver.behaviour();
        byte[] bytes = body == null ? new byte[0] : body;

        String signature = "NOT_CHECKED";
        if (behaviour.secret() != null) {
            signature = SignatureVerifier.verify(behaviour.secret(), request.getHeader("webhook-id"),
                    request.getHeader("webhook-timestamp"), request.getHeader("webhook-signature"), bytes,
                    Instant.now(), SignatureVerifier.DEFAULT_TOLERANCE).name();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (String header : Collections.list(request.getHeaderNames())) {
            headers.put(header.toLowerCase(), request.getHeader(header));
        }
        ReceivedRequest recorded = receiver.record(Instant.now(), request.getHeader("webhook-id"),
                request.getHeader("x-webhook-delivery-id"), request.getHeader("x-webhook-attempt"), signature,
                behaviour.status(), headers, new String(bytes, StandardCharsets.UTF_8));

        if (behaviour.delayMs() > 0) {
            Thread.sleep(behaviour.delayMs());
        }
        return ResponseEntity.status(behaviour.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("receiver", name, "sequence", recorded.sequence(), "duplicate", recorded.duplicate(),
                        "signature", signature));
    }

    @PutMapping("/receivers/{name}/config")
    Behaviour configure(@PathVariable String name, @RequestBody Behaviour behaviour) {
        if (behaviour.status() < 100 || behaviour.status() > 599 || behaviour.delayMs() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be 100-599 and delayMs >= 0");
        }
        registry.get(name).configure(behaviour);
        return behaviour;
    }

    @GetMapping("/receivers/{name}/config")
    Behaviour config(@PathVariable String name) {
        return registry.get(name).behaviour();
    }

    @GetMapping("/receivers/{name}/requests")
    List<ReceivedRequest> requests(@PathVariable String name) {
        return registry.get(name).requests();
    }

    @DeleteMapping("/receivers/{name}")
    ResponseEntity<Void> reset(@PathVariable String name) {
        registry.reset(name);
        return ResponseEntity.noContent().build();
    }
}
