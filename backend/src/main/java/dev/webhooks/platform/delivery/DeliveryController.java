package dev.webhooks.platform.delivery;

import dev.webhooks.platform.auth.AccountPrincipal;
import dev.webhooks.platform.common.ApiException;
import dev.webhooks.platform.common.Cursor;
import dev.webhooks.platform.common.Page;
import dev.webhooks.platform.delivery.DeliveryDtos.DeliveryDetail;
import dev.webhooks.platform.delivery.DeliveryDtos.DeliverySummary;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/deliveries")
public class DeliveryController {

    private final DeliveryQueryRepository deliveries;
    private final ObjectMapper objectMapper;

    public DeliveryController(DeliveryQueryRepository deliveries, ObjectMapper objectMapper) {
        this.deliveries = deliveries;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    Page<DeliverySummary> list(@AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam(required = false) List<DeliveryStatus> status,
            @RequestParam(required = false) UUID endpointId,
            @RequestParam(required = false) UUID eventId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        int size = Page.checkLimit(limit);
        var filter = new DeliveryQueryRepository.Filter(status, endpointId, eventId, eventType);
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
        var found = deliveries.find(principal.accountId(), id).orElseThrow(() -> ApiException.notFound("Delivery"));
        return new DeliveryDetail(found.summary(), found.endpointUrl(),
                objectMapper.readTree(deliveries.eventBody(found.summary().eventId())),
                deliveries.attempts(id));
    }
}
