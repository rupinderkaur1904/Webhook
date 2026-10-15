package dev.webhooks.platform.event;

import dev.webhooks.platform.common.ApiException;
import dev.webhooks.platform.config.WebhookProperties;
import dev.webhooks.platform.crypto.Tokens;
import dev.webhooks.platform.delivery.DeliveryRepository;
import dev.webhooks.platform.delivery.DeliveryStatus;
import dev.webhooks.platform.event.EventDtos.AcceptedDelivery;
import dev.webhooks.platform.event.EventDtos.CreateEventRequest;
import dev.webhooks.platform.event.EventDtos.EventAcceptedResponse;
import dev.webhooks.platform.event.EventRepository.NewEvent;
import dev.webhooks.platform.event.EventRepository.StoredEvent;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Accepts events. The event, its idempotency record and one PENDING delivery per target endpoint are
 * written in a single transaction that commits before the caller gets {@code 202}; no destination
 * is contacted here.
 */
@Service
public class IngestionService {

    public static final String TEST_EVENT_TYPE = "webhook.test";

    private final EventRepository events;
    private final DeliveryRepository deliveries;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final WebhookProperties properties;

    public IngestionService(EventRepository events, DeliveryRepository deliveries, JdbcClient jdbc,
            ObjectMapper objectMapper, TransactionTemplate tx, WebhookProperties properties) {
        this.events = events;
        this.deliveries = deliveries;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.tx = tx;
        this.properties = properties;
    }

    /** Outcome of an ingestion call: {@code replayed} means the idempotency key matched an earlier request. */
    public record IngestionResult(EventAcceptedResponse response, boolean replayed) {}

    public IngestionResult ingest(UUID accountId, String idempotencyKey, CreateEventRequest request) {
        validateIdempotencyKey(idempotencyKey);
        if (request.payload() == null || !request.payload().isObject()) {
            throw ApiException.validation("payload", "must be a JSON object");
        }
        List<UUID> endpointIds = request.endpointIds() == null ? null
                : request.endpointIds().stream().distinct().sorted().toList();
        Instant occurredAt = request.occurredAt() == null ? null
                : request.occurredAt().toInstant().truncatedTo(ChronoUnit.MICROS);
        byte[] requestHash = requestHash(request.eventType(), request.payload(), endpointIds, occurredAt);

        UUID eventId = UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String body = envelope(eventId, request.eventType(), createdAt, occurredAt, request.payload());
        NewEvent event = new NewEvent(eventId, accountId, request.eventType(), idempotencyKey, requestHash, body,
                occurredAt, createdAt);

        return tx.execute(status -> {
            if (!events.insertIfKeyUnused(event)) {
                // The key exists (possibly just committed by a concurrent request that we waited on).
                StoredEvent existing = events.findByIdempotencyKey(accountId, idempotencyKey).orElseThrow();
                if (!MessageDigest.isEqual(existing.requestHash(), requestHash)) {
                    throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                            "This Idempotency-Key was already used with a different request");
                }
                return new IngestionResult(response(existing), true);
            }
            List<UUID> targets = endpointIds == null
                    ? subscribedEndpoints(accountId, request.eventType())
                    : checkExplicitEndpoints(accountId, endpointIds);
            for (UUID endpointId : targets) {
                deliveries.insertPending(UUID.randomUUID(), accountId, eventId, endpointId,
                        properties.retry().maxAttempts());
            }
            return new IngestionResult(response(events.find(accountId, eventId).orElseThrow()), false);
        });
    }

    /** Creates a real {@code webhook.test} event targeted at one endpoint. */
    public EventAcceptedResponse sendTestEvent(UUID accountId, UUID endpointId) {
        Boolean enabled = jdbc.sql("SELECT enabled FROM endpoints WHERE id = :id AND account_id = :account AND deleted_at IS NULL")
                .param("id", endpointId)
                .param("account", accountId)
                .query(Boolean.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Endpoint"));
        if (!enabled) {
            throw new ApiException(HttpStatus.CONFLICT, "ENDPOINT_DISABLED", "Enable the endpoint before sending a test event");
        }
        ObjectNode payload = objectMapper.createObjectNode()
                .put("message", "Test event from the webhook delivery platform")
                .put("endpointId", endpointId.toString());
        var request = new CreateEventRequest(TEST_EVENT_TYPE, payload, List.of(endpointId), null);
        return ingest(accountId, "test:" + UUID.randomUUID(), request).response();
    }

    private List<UUID> subscribedEndpoints(UUID accountId, String eventType) {
        return jdbc.sql("""
                        SELECT id FROM endpoints
                        WHERE account_id = :account AND enabled AND deleted_at IS NULL
                          AND (cardinality(event_types) = 0
                               OR EXISTS (SELECT 1 FROM unnest(event_types) t
                                          WHERE t = :type
                                             OR (right(t, 2) = '.*' AND starts_with(:type, left(t, -1)))))
                        ORDER BY created_at, id
                        """)
                .param("account", accountId)
                .param("type", eventType)
                .query(UUID.class)
                .list();
    }

    private List<UUID> checkExplicitEndpoints(UUID accountId, List<UUID> endpointIds) {
        record Row(UUID id, boolean enabled) {}
        List<Row> rows = jdbc.sql("""
                        SELECT id, enabled FROM endpoints
                        WHERE account_id = :account AND deleted_at IS NULL AND id = ANY(:ids)
                        """)
                .param("account", accountId)
                .param("ids", endpointIds.toArray(UUID[]::new))
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getBoolean("enabled")))
                .list();
        Set<UUID> found = rows.stream().map(Row::id).collect(Collectors.toSet());
        Collection<UUID> missing = endpointIds.stream().filter(id -> !found.contains(id))
                .collect(Collectors.toCollection(TreeSet::new));
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ENDPOINT_NOT_FOUND",
                    "Unknown endpoint ids: " + missing,
                    List.of(new ApiException.FieldViolation("endpointIds", "unknown endpoint ids: " + missing)));
        }
        List<UUID> disabled = rows.stream().filter(r -> !r.enabled()).map(Row::id).sorted().toList();
        if (!disabled.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ENDPOINT_DISABLED",
                    "Disabled endpoints cannot receive events: " + disabled,
                    List.of(new ApiException.FieldViolation("endpointIds", "disabled endpoints: " + disabled)));
        }
        return endpointIds;
    }

    private EventAcceptedResponse response(StoredEvent event) {
        List<AcceptedDelivery> accepted = jdbc.sql("""
                        SELECT id, endpoint_id, status FROM deliveries
                        WHERE event_id = :event AND replay_of_delivery_id IS NULL
                        ORDER BY created_at, id
                        """)
                .param("event", event.id())
                .query((rs, n) -> new AcceptedDelivery(rs.getObject("id", UUID.class),
                        rs.getObject("endpoint_id", UUID.class), DeliveryStatus.valueOf(rs.getString("status"))))
                .list();
        return new EventAcceptedResponse(event.id(), event.eventType(), event.idempotencyKey(), event.createdAt(),
                new ArrayList<>(accepted));
    }

    /** The exact JSON body sent to receivers; built once so every attempt is byte-identical. */
    private String envelope(UUID id, String type, Instant createdAt, Instant occurredAt, JsonNode payload) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("id", id.toString());
        body.put("type", type);
        body.put("createdAt", createdAt.toString());
        if (occurredAt != null) {
            body.put("occurredAt", occurredAt.toString());
        }
        body.set("data", payload);
        return objectMapper.writeValueAsString(body);
    }

    private byte[] requestHash(String eventType, JsonNode payload, List<UUID> endpointIds, Instant occurredAt) {
        ObjectNode fingerprint = objectMapper.createObjectNode();
        fingerprint.put("eventType", eventType);
        fingerprint.set("payload", CanonicalJson.canonicalize(payload));
        if (endpointIds != null) {
            var ids = fingerprint.putArray("endpointIds");
            endpointIds.forEach(id -> ids.add(id.toString()));
        }
        if (occurredAt != null) {
            fingerprint.put("occurredAt", occurredAt.toString());
        }
        return Tokens.sha256(objectMapper.writeValueAsString(fingerprint));
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "The Idempotency-Key header is required");
        }
        if (key.length() > 255 || !key.chars().allMatch(c -> c >= 0x21 && c <= 0x7e)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key must be 1-255 printable ASCII characters without spaces");
        }
    }
}
