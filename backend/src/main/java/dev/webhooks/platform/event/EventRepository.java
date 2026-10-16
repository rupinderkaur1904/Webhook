package dev.webhooks.platform.event;

import static dev.webhooks.platform.common.Jdbc.instant;
import static dev.webhooks.platform.common.Jdbc.offset;

import dev.webhooks.platform.common.Cursor;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EventRepository {

    private final JdbcClient jdbc;

    public EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the event unless the idempotency key is already used. Returns true if inserted. */
    public boolean insertIfKeyUnused(NewEvent e) {
        return jdbc.sql("""
                        INSERT INTO events (id, account_id, event_type, idempotency_key, request_hash, body,
                                            occurred_at, created_at)
                        VALUES (:id, :account, :type, :key, :hash, :body, :occurredAt, :createdAt)
                        ON CONFLICT (account_id, idempotency_key) DO NOTHING
                        """)
                .param("id", e.id())
                .param("account", e.accountId())
                .param("type", e.eventType())
                .param("key", e.idempotencyKey())
                .param("hash", e.requestHash())
                .param("body", e.body())
                .param("occurredAt", offset(e.occurredAt()))
                .param("createdAt", offset(e.createdAt()))
                .update() == 1;
    }

    public Optional<StoredEvent> findByIdempotencyKey(UUID accountId, String key) {
        return jdbc.sql("SELECT * FROM events WHERE account_id = :account AND idempotency_key = :key")
                .param("account", accountId)
                .param("key", key)
                .query(EventRepository::mapStored)
                .optional();
    }

    public Optional<StoredEvent> find(UUID accountId, UUID id) {
        return jdbc.sql("SELECT * FROM events WHERE account_id = :account AND id = :id")
                .param("account", accountId)
                .param("id", id)
                .query(EventRepository::mapStored)
                .optional();
    }

    /**
     * Events newest first, with per-status delivery counts. {@code from} is inclusive, {@code to} exclusive.
     * The page of events is selected first (keyset scan of {@code events_account_created_idx}) and the
     * counts are computed for that page only, so the cost does not grow with the account's history.
     */
    public List<EventDtos.EventSummary> list(UUID accountId, String eventType, Instant from, Instant to, Cursor cursor,
            int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.id, e.event_type, e.idempotency_key, e.created_at,
                       c.delivery_count, c.delivered_count, c.dead_lettered_count, c.active_count
                FROM (
                    SELECT e.id, e.event_type, e.idempotency_key, e.created_at
                    FROM events e
                    WHERE e.account_id = :account
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("account", accountId);
        if (eventType != null) {
            sql.append(" AND e.event_type = :type");
            params.put("type", eventType);
        }
        if (from != null) {
            sql.append(" AND e.created_at >= :from");
            params.put("from", offset(from));
        }
        if (to != null) {
            sql.append(" AND e.created_at < :to");
            params.put("to", offset(to));
        }
        if (cursor != null) {
            sql.append(" AND (e.created_at, e.id) < (:cursorAt, :cursorId)");
            params.put("cursorAt", offset(cursor.createdAt()));
            params.put("cursorId", cursor.id());
        }
        sql.append("""
                     ORDER BY e.created_at DESC, e.id DESC LIMIT :limit
                ) e
                LEFT JOIN LATERAL (
                    SELECT count(*) AS delivery_count,
                           count(*) FILTER (WHERE d.status = 'DELIVERED') AS delivered_count,
                           count(*) FILTER (WHERE d.status = 'DEAD_LETTERED') AS dead_lettered_count,
                           count(*) FILTER (WHERE d.status IN ('PENDING', 'RETRYING', 'IN_FLIGHT')) AS active_count
                    FROM deliveries d
                    WHERE d.event_id = e.id
                ) c ON true
                ORDER BY e.created_at DESC, e.id DESC
                """);
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query((rs, n) -> new EventDtos.EventSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("idempotency_key"),
                        instant(rs, "created_at"),
                        rs.getInt("delivery_count"),
                        rs.getInt("delivered_count"),
                        rs.getInt("dead_lettered_count"),
                        rs.getInt("active_count")))
                .list();
    }

    private static StoredEvent mapStored(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new StoredEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("idempotency_key"),
                rs.getBytes("request_hash"),
                rs.getString("body"),
                instant(rs, "occurred_at"),
                instant(rs, "created_at"));
    }

    public record NewEvent(UUID id, UUID accountId, String eventType, String idempotencyKey, byte[] requestHash,
            String body, Instant occurredAt, Instant createdAt) {}

    public record StoredEvent(UUID id, UUID accountId, String eventType, String idempotencyKey, byte[] requestHash,
            String body, Instant occurredAt, Instant createdAt) {}
}
