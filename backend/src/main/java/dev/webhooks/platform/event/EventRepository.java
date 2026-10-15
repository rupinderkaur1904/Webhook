package dev.webhooks.platform.event;

import static dev.webhooks.platform.common.Jdbc.instant;
import static dev.webhooks.platform.common.Jdbc.offset;

import dev.webhooks.platform.common.Cursor;
import java.time.Instant;
import java.util.List;
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

    public List<EventDtos.EventSummary> list(UUID accountId, String eventType, Cursor cursor, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.id, e.event_type, e.idempotency_key, e.created_at,
                       (SELECT count(*) FROM deliveries d WHERE d.event_id = e.id) AS delivery_count
                FROM events e
                WHERE e.account_id = :account
                """);
        if (eventType != null) {
            sql.append(" AND e.event_type = :type");
        }
        if (cursor != null) {
            sql.append(" AND (e.created_at, e.id) < (:cursorAt, :cursorId)");
        }
        sql.append(" ORDER BY e.created_at DESC, e.id DESC LIMIT :limit");
        var statement = jdbc.sql(sql.toString()).param("account", accountId).param("limit", limit);
        if (eventType != null) {
            statement = statement.param("type", eventType);
        }
        if (cursor != null) {
            statement = statement.param("cursorAt", offset(cursor.createdAt())).param("cursorId", cursor.id());
        }
        return statement.query((rs, n) -> new EventDtos.EventSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("idempotency_key"),
                        instant(rs, "created_at"),
                        rs.getInt("delivery_count")))
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
