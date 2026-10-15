package dev.webhooks.simulator;

import dev.webhooks.simulator.ReceiverRegistry.Behaviour;
import dev.webhooks.simulator.ReceiverRegistry.ReceivedRequest;
import dev.webhooks.simulator.ReceiverRegistry.Response;
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
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Webhook target: {@code POST /receivers/{name}}. Control API under the same path:
 * {@code PUT .../config}, {@code GET .../requests}, {@code DELETE /receivers/{name}}; plus
 * {@code POST /verify} for checking a signature by hand.
 */
@RestController
public class ReceiverController {

    private final ReceiverRegistry registry;

    public ReceiverController(ReceiverRegistry registry) {
        this.registry = registry;
    }

    /** Liveness for the container health check and the hosting platform. */
    @GetMapping("/healthz")
    Map<String, String> health() {
        return Map.of("status", "UP");
    }

    /** Paths an endpoint URL commonly stops at by mistake (e.g. {@code http://simulator:9090}). */
    static final List<String> WRONG_PATHS = List.of("/", "/receivers", "/receivers/");

    /**
     * A delivery to the simulator's root instead of a named receiver. The platform records this body on
     * the attempt, so the dashboard shows how to fix the endpoint URL.
     */
    @PostMapping({"/", "/receivers", "/receivers/"})
    ResponseEntity<Map<String, Object>> wrongPath(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "No receiver at " + request.getRequestURI(),
                "hint", "Webhooks go to /receivers/{name}, for example http://simulator:9090/receivers/demo. "
                        + "Any name works and is created on first use."));
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
        Response response = receiver.nextResponse();
        ReceivedRequest recorded = receiver.record(Instant.now(), request.getHeader("webhook-id"),
                request.getHeader("x-webhook-delivery-id"), request.getHeader("x-webhook-attempt"), signature,
                response, headers, new String(bytes, StandardCharsets.UTF_8));

        if (response.delayMs() > 0) {
            Thread.sleep(response.delayMs());
        }
        return ResponseEntity.status(response.status())
                .headers(h -> response.headers().forEach(h::set))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("receiver", name, "sequence", recorded.sequence(), "duplicate", recorded.duplicate(),
                        "signature", signature));
    }

    @PutMapping("/receivers/{name}/config")
    Behaviour configure(@PathVariable String name, @RequestBody Behaviour behaviour) {
        String problem = behaviour.validate();
        if (problem != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem);
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

    /**
     * Checks a signature the way a receiver would, for demonstrating that a tampered body or a
     * wrong secret is rejected. Uses the current time for the timestamp tolerance check.
     */
    @PostMapping("/verify")
    Map<String, String> verify(@RequestBody VerifyRequest request) {
        if (request.secret() == null || request.body() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secret and body are required");
        }
        var result = SignatureVerifier.verify(request.secret(), request.webhookId(), request.webhookTimestamp(),
                request.webhookSignature(), request.body().getBytes(StandardCharsets.UTF_8), Instant.now(),
                SignatureVerifier.DEFAULT_TOLERANCE);
        return Map.of("result", result.name());
    }

    record VerifyRequest(String secret, String webhookId, String webhookTimestamp, String webhookSignature,
            String body) {}

    @DeleteMapping("/receivers/{name}")
    ResponseEntity<Void> reset(@PathVariable String name) {
        registry.reset(name);
        return ResponseEntity.noContent().build();
    }

    /** A chunked body that passed the 1 MB limit while being read; other unreadable bodies are 400. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Void> unreadable(HttpMessageNotReadableException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PublicExposureFilter.BodyTooLargeException) {
                return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
            }
        }
        return ResponseEntity.badRequest().build();
    }
}
