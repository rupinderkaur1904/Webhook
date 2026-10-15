package dev.webhooks.platform.event;

import dev.webhooks.platform.auth.AccountPrincipal;
import dev.webhooks.platform.common.ApiException;
import dev.webhooks.platform.common.Cursor;
import dev.webhooks.platform.common.Page;
import dev.webhooks.platform.delivery.DeliveryQueryRepository;
import dev.webhooks.platform.event.EventDtos.CreateEventRequest;
import dev.webhooks.platform.event.EventDtos.EventAcceptedResponse;
import dev.webhooks.platform.event.EventDtos.EventDetail;
import dev.webhooks.platform.event.EventDtos.EventSummary;
import dev.webhooks.platform.ratelimit.RateLimiter;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final IngestionService ingestion;
    private final EventRepository events;
    private final DeliveryQueryRepository deliveries;
    private final ObjectMapper objectMapper;
    private final RateLimiter rateLimiter;

    public EventController(IngestionService ingestion, EventRepository events, DeliveryQueryRepository deliveries,
            ObjectMapper objectMapper, RateLimiter rateLimiter) {
        this.ingestion = ingestion;
        this.events = events;
        this.deliveries = deliveries;
        this.objectMapper = objectMapper;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Accepts an event for asynchronous delivery. Returns {@code 202} once the event and its
     * deliveries are committed. Repeating a request with the same {@code Idempotency-Key} returns
     * {@code 200} with the original response and {@code Idempotent-Replayed: true}; reusing the key
     * for a different request returns {@code 409}. Limited per credential; over the limit returns
     * {@code 429} with {@code Retry-After}.
     */
    @PostMapping
    ResponseEntity<EventAcceptedResponse> submit(@AuthenticationPrincipal AccountPrincipal principal,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateEventRequest request) {
        rateLimiter.checkIngest(principal.credentialId());
        var result = ingestion.ingest(principal.accountId(), idempotencyKey, request);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.response());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result.response());
    }

    @GetMapping
    Page<EventSummary> list(@AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        int size = Page.checkLimit(limit);
        List<EventSummary> rows = events.list(principal.accountId(), eventType, from, to,
                cursor == null ? null : Cursor.decode(cursor), size + 1);
        String next = null;
        if (rows.size() > size) {
            rows = rows.subList(0, size);
            EventSummary last = rows.getLast();
            next = new Cursor(last.createdAt(), last.id()).encode();
        }
        return new Page<>(rows, next);
    }

    @GetMapping("/{id}")
    EventDetail get(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id) {
        var event = events.find(principal.accountId(), id).orElseThrow(() -> ApiException.notFound("Event"));
        return new EventDetail(event.id(), event.eventType(), event.idempotencyKey(), event.createdAt(),
                event.occurredAt(), objectMapper.readTree(event.body()),
                deliveries.listForEvent(principal.accountId(), id));
    }
}
