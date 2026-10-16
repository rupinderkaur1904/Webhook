package dev.webhooks.platform.endpoint;

import static dev.webhooks.platform.common.Jdbc.instant;
import static dev.webhooks.platform.common.Jdbc.offset;

import dev.webhooks.platform.circuit.CircuitState;
import dev.webhooks.platform.circuit.CircuitView;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EndpointRepository {

    private static final String COLUMNS = """
            id, account_id, name, description, url, event_types, enabled, secret_prefix,
            CASE WHEN previous_secret_expires_at > now() THEN previous_secret_expires_at END AS previous_secret_expires_at,
            circuit_state, consecutive_failures, circuit_opened_at, circuit_retry_at,
            created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public EndpointRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Endpoint e, byte[] secretCiphertext) {
        jdbc.sql("""
                        INSERT INTO endpoints (id, account_id, name, description, url, event_types, enabled,
                                               secret_ciphertext, secret_prefix)
                        VALUES (:id, :account, :name, :description, :url, :eventTypes, :enabled, :secret, :prefix)
                        """)
                .param("id", e.id())
                .param("account", e.accountId())
                .param("name", e.name())
                .param("description", e.description())
                .param("url", e.url())
                .param("eventTypes", e.eventTypes().toArray(String[]::new))
                .param("enabled", e.enabled())
                .param("secret", secretCiphertext)
                .param("prefix", e.secretPrefix())
                .update();
    }

    public Optional<Endpoint> find(UUID accountId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM endpoints WHERE id = :id AND account_id = :account AND deleted_at IS NULL")
                .param("id", id)
                .param("account", accountId)
                .query(EndpointRepository::map)
                .optional();
    }

    /**
     * Like {@link #find} but locks the row for the rest of the transaction, so a read-modify-write
     * (a partial PATCH) cannot overwrite a concurrent update with stale values.
     */
    public Optional<Endpoint> findForUpdate(UUID accountId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM endpoints WHERE id = :id AND account_id = :account AND deleted_at IS NULL FOR UPDATE")
                .param("id", id)
                .param("account", accountId)
                .query(EndpointRepository::map)
                .optional();
    }

    public List<Endpoint> list(UUID accountId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM endpoints WHERE account_id = :account AND deleted_at IS NULL
                        ORDER BY created_at DESC, id DESC
                        """)
                .param("account", accountId)
                .query(EndpointRepository::map)
                .list();
    }

    public void update(Endpoint e) {
        jdbc.sql("""
                        UPDATE endpoints
                        SET name = :name, description = :description, url = :url, event_types = :eventTypes,
                            enabled = :enabled, updated_at = now()
                        WHERE id = :id AND account_id = :account AND deleted_at IS NULL
                        """)
                .param("id", e.id())
                .param("account", e.accountId())
                .param("name", e.name())
                .param("description", e.description())
                .param("url", e.url())
                .param("eventTypes", e.eventTypes().toArray(String[]::new))
                .param("enabled", e.enabled())
                .update();
    }

    /** Soft-deletes the endpoint and cancels its queued deliveries. Returns false if not found. */
    public boolean softDelete(UUID accountId, UUID id) {
        int updated = jdbc.sql("""
                        UPDATE endpoints SET deleted_at = now(), enabled = false, updated_at = now()
                        WHERE id = :id AND account_id = :account AND deleted_at IS NULL
                        """)
                .param("id", id)
                .param("account", accountId)
                .update();
        if (updated == 0) {
            return false;
        }
        jdbc.sql("""
                        UPDATE deliveries SET status = 'CANCELLED', next_attempt_at = NULL, updated_at = now()
                        WHERE endpoint_id = :id AND status IN ('PENDING', 'RETRYING')
                        """)
                .param("id", id)
                .update();
        return true;
    }

    public int countActive(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM endpoints WHERE account_id = :account AND deleted_at IS NULL")
                .param("account", accountId)
                .query(Integer.class)
                .single();
    }

    /**
     * Serialises endpoint creation per account (transaction-scoped advisory lock), so concurrent
     * creates cannot both pass the per-account cap.
     */
    public void lockAccountForCreate(UUID accountId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('endpoints:' || :account, 0))")
                .param("account", accountId.toString())
                .query((rs, n) -> 1)
                .single();
    }

    /**
     * Replaces the signing secret. The old secret keeps signing alongside the new one until
     * {@code previousExpiresAt}; a zero grace period drops it immediately. Returns false if not found.
     */
    public boolean rotateSecret(UUID accountId, UUID id, byte[] newCiphertext, String newPrefix,
            Instant previousExpiresAt) {
        return jdbc.sql("""
                        UPDATE endpoints
                        SET previous_secret_ciphertext = CASE WHEN CAST(:keepPrevious AS boolean) THEN secret_ciphertext END,
                            previous_secret_expires_at = CAST(:expiresAt AS timestamptz),
                            secret_ciphertext = :secret, secret_prefix = :prefix, updated_at = now()
                        WHERE id = :id AND account_id = :account AND deleted_at IS NULL
                        """)
                .param("keepPrevious", previousExpiresAt != null)
                .param("expiresAt", previousExpiresAt == null ? null : offset(previousExpiresAt))
                .param("secret", newCiphertext)
                .param("prefix", newPrefix)
                .param("id", id)
                .param("account", accountId)
                .update() == 1;
    }

    /** Secret material for signing: current secret, and the previous one while its grace period lasts. */
    public Optional<SigningSecrets> findSigningSecrets(UUID endpointId) {
        return jdbc.sql("""
                        SELECT secret_ciphertext,
                               CASE WHEN previous_secret_expires_at > now() THEN previous_secret_ciphertext END AS previous
                        FROM endpoints WHERE id = :id
                        """)
                .param("id", endpointId)
                .query((rs, n) -> new SigningSecrets(rs.getBytes("secret_ciphertext"), rs.getBytes("previous")))
                .optional();
    }

    public record SigningSecrets(byte[] current, byte[] previous) {}

    static Endpoint map(ResultSet rs, int row) throws SQLException {
        Array types = rs.getArray("event_types");
        return new Endpoint(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("url"),
                types == null ? List.of() : Arrays.asList((String[]) types.getArray()),
                rs.getBoolean("enabled"),
                rs.getString("secret_prefix"),
                instant(rs, "previous_secret_expires_at"),
                new CircuitView(CircuitState.valueOf(rs.getString("circuit_state")), rs.getInt("consecutive_failures"),
                        instant(rs, "circuit_opened_at"), instant(rs, "circuit_retry_at")),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }
}
