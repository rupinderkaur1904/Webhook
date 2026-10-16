package dev.webhooks.platform.delivery;

import static dev.webhooks.platform.common.Jdbc.instant;
import static dev.webhooks.platform.common.Jdbc.integer;
import static dev.webhooks.platform.common.Jdbc.offset;

import dev.webhooks.platform.common.Cursor;
import dev.webhooks.platform.delivery.DeliveryDtos.AttemptView;
import dev.webhooks.platform.delivery.DeliveryDtos.DeliverySummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Read-side queries for the API and dashboard. Every query is scoped to one account. */
@Repository
public class DeliveryQueryRepository {

    private static final String SUMMARY_SELECT = """
            SELECT d.*, e.name AS endpoint_name, e.url AS endpoint_url, ev.event_type
            FROM deliveries d
            JOIN endpoints e ON e.id = d.endpoint_id
            JOIN events ev ON ev.id = d.event_id
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public DeliveryQueryRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record Filter(List<DeliveryStatus> statuses, UUID endpointId, UUID eventId, String eventType,
            DeadLetterReason deadLetterReason, Instant from, Instant to) {

        public Filter(List<DeliveryStatus> statuses, UUID endpointId, UUID eventId, String eventType) {
            this(statuses, endpointId, eventId, eventType, null, null, null);
        }
    }

    public List<DeliverySummary> list(UUID accountId, Filter filter, Cursor cursor, int limit) {
        StringBuilder sql = new StringBuilder(SUMMARY_SELECT).append(" WHERE d.account_id = :account");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("account", accountId);
        if (filter.statuses() != null && !filter.statuses().isEmpty()) {
            sql.append(" AND d.status = ANY(:statuses)");
            params.put("statuses", filter.statuses().stream().map(Enum::name).toArray(String[]::new));
        }
        if (filter.endpointId() != null) {
            sql.append(" AND d.endpoint_id = :endpointId");
            params.put("endpointId", filter.endpointId());
        }
        if (filter.eventId() != null) {
            sql.append(" AND d.event_id = :eventId");
            params.put("eventId", filter.eventId());
        }
        if (filter.eventType() != null) {
            // The account condition on ev lets PostgreSQL use events_account_type_created_idx instead of
            // scanning every tenant's events for the type (an event and its deliveries share the account).
            sql.append(" AND ev.account_id = :account AND ev.event_type = :eventType");
            params.put("eventType", filter.eventType());
        }
        if (filter.deadLetterReason() != null) {
            sql.append(" AND d.dead_letter_reason = :reason");
            params.put("reason", filter.deadLetterReason().name());
        }
        if (filter.from() != null) {
            sql.append(" AND d.created_at >= :from");
            params.put("from", offset(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND d.created_at < :to");
            params.put("to", offset(filter.to()));
        }
        if (cursor != null) {
            sql.append(" AND (d.created_at, d.id) < (:cursorAt, :cursorId)");
            params.put("cursorAt", offset(cursor.createdAt()));
            params.put("cursorId", cursor.id());
        }
        sql.append(" ORDER BY d.created_at DESC, d.id DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query(DeliveryQueryRepository::mapSummary).list();
    }

    public List<DeliverySummary> listForEvent(UUID accountId, UUID eventId) {
        return jdbc.sql(SUMMARY_SELECT + " WHERE d.account_id = :account AND d.event_id = :eventId ORDER BY d.created_at, d.id")
                .param("account", accountId)
                .param("eventId", eventId)
                .query(DeliveryQueryRepository::mapSummary)
                .list();
    }

    /** The original delivery and all its replays, oldest first. */
    public List<DeliverySummary> chain(UUID accountId, UUID rootDeliveryId) {
        return jdbc.sql(SUMMARY_SELECT + " WHERE d.account_id = :account AND d.root_delivery_id = :root ORDER BY d.created_at, d.id")
                .param("account", accountId)
                .param("root", rootDeliveryId)
                .query(DeliveryQueryRepository::mapSummary)
                .list();
    }

    public Optional<SummaryWithContext> find(UUID accountId, UUID id) {
        return jdbc.sql(SUMMARY_SELECT + " WHERE d.account_id = :account AND d.id = :id")
                .param("account", accountId)
                .param("id", id)
                .query((rs, n) -> new SummaryWithContext(mapSummary(rs, n), rs.getString("endpoint_url"),
                        rs.getString("lease_owner") == null ? null
                                : new DeliveryDtos.Lease(rs.getString("lease_owner"), instant(rs, "lease_expires_at"))))
                .optional();
    }

    public String eventBody(UUID eventId) {
        return jdbc.sql("SELECT body FROM events WHERE id = :id").param("id", eventId).query(String.class).single();
    }

    public List<AttemptView> attempts(UUID deliveryId) {
        return jdbc.sql("SELECT * FROM delivery_attempts WHERE delivery_id = :id ORDER BY attempt_number")
                .param("id", deliveryId)
                .query((rs, n) -> new AttemptView(
                        rs.getObject("id", UUID.class),
                        rs.getInt("attempt_number"),
                        rs.getString("status"),
                        rs.getString("outcome"),
                        (Boolean) rs.getObject("retryable"),
                        rs.getString("request_url"),
                        integer(rs, "response_status"),
                        headers(rs.getString("response_headers")),
                        rs.getString("response_body_excerpt"),
                        rs.getString("error_message"),
                        integer(rs, "retry_after_seconds"),
                        instant(rs, "started_at"),
                        instant(rs, "finished_at"),
                        integer(rs, "duration_ms"),
                        rs.getBoolean("reported_after_lease_expiry")))
                .list();
    }

    private Map<String, String> headers(String json) {
        if (json == null) {
            return Map.of();
        }
        return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
    }

    public record SummaryWithContext(DeliverySummary summary, String endpointUrl, DeliveryDtos.Lease lease) {}

    static DeliverySummary mapSummary(ResultSet rs, int row) throws SQLException {
        String reason = rs.getString("dead_letter_reason");
        return new DeliverySummary(
                rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getObject("endpoint_id", UUID.class),
                rs.getString("endpoint_name"),
                rs.getString("event_type"),
                DeliveryStatus.valueOf(rs.getString("status")),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts"),
                integer(rs, "last_response_status"),
                rs.getString("last_error"),
                instant(rs, "last_attempt_at"),
                instant(rs, "next_attempt_at"),
                reason == null ? null : DeadLetterReason.valueOf(reason),
                instant(rs, "delivered_at"),
                instant(rs, "dead_lettered_at"),
                rs.getObject("replay_of_delivery_id", UUID.class),
                rs.getObject("root_delivery_id", UUID.class),
                instant(rs, "created_at"));
    }
}
