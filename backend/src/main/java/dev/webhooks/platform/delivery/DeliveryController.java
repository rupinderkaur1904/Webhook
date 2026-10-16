package dev.webhooks.platform.delivery;

import dev.webhooks.platform.auth.AccountPrincipal;
import dev.webhooks.platform.common.ApiException;
import dev.webhooks.platform.common.Cursor;
import dev.webhooks.platform.common.Page;
import dev.webhooks.platform.delivery.DeliveryDtos.DeliveryDetail;
import dev.webhooks.platform.delivery.DeliveryDtos.DeliverySummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/deliveries")
public class DeliveryController {

    private final DeliveryQueryRepository deliveries;
    private final ReplayService replays;
    private final ObjectMapper objectMapper;

    public DeliveryController(DeliveryQueryRepository deliveries, ReplayService replays, ObjectMapper objectMapper) {
        this.deliveries = deliveries;
        this.replays = replays;
        this.objectMapper = objectMapper;
    }

    /**
     * Lists deliveries, newest first. The dead-letter view is {@code status=DEAD_LETTERED}, optionally
     * narrowed by {@code deadLetterReason}. {@code from} (inclusive) and {@code to} (exclusive) bound
     * the creation time.
     */
    @GetMapping
    Page<DeliverySummary> list(@AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam(required = false) List<DeliveryStatus> status,
            @RequestParam(required = false) UUID endpointId,
            @RequestParam(required = false) UUID eventId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) DeadLetterReason deadLetterReason,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        int size = Page.checkLimit(limit);
        var filter = new DeliveryQueryRepository.Filter(status, endpointId, eventId, eventType, deadLetterReason,
                Page.checkTime("from", from), Page.checkTime("to", to));
        List<DeliverySummary> rows = deliveries.list(principal.accountId(), filter,
                cursor == null ? null : Cursor.decode(cursor), size + 1);
        String next = null;
        if (rows.size() > size) {
            rows = rows.subList(0, size);
            DeliverySummary last = rows.getLast();
            next = new Cursor(last.createdAt(), last.id()).encode();
        }
        return new Page<>(rows, next);
    }

    @GetMapping("/{id}")
    DeliveryDetail get(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id) {
        UUID account = principal.accountId();
        var found = deliveries.find(account, id).orElseThrow(() -> ApiException.notFound("Delivery"));
        DeliverySummary summary = found.summary();
        return new DeliveryDetail(summary, found.endpointUrl(), found.lease(),
                objectMapper.readTree(deliveries.eventBody(summary.eventId())),
                deliveries.attempts(id),
                replays.eligibility(account, id).orElseThrow(() -> ApiException.notFound("Delivery")),
                deliveries.chain(account, summary.rootDeliveryId()));
    }

    /**
     * Replays a dead-lettered delivery: creates a new delivery of the same event to the same endpoint
     * with a fresh attempt budget. Returns {@code 202} with the new delivery, or {@code 409
     * REPLAY_NOT_ELIGIBLE} with a {@code reason}.
     */
    @PostMapping("/{id}/replay")
    ResponseEntity<DeliverySummary> replay(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(replays.replay(principal.accountId(), id));
    }
}
