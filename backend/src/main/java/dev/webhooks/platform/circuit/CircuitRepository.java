package dev.webhooks.platform.circuit;

import static dev.webhooks.platform.common.Jdbc.instant;
import static dev.webhooks.platform.common.Jdbc.offset;

import dev.webhooks.platform.circuit.CircuitBreaker.State;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CircuitRepository {

    private final JdbcClient jdbc;

    public CircuitRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks the endpoint row for the rest of the transaction and returns its circuit state. */
    public Optional<State> lock(UUID endpointId) {
        return jdbc.sql("""
                        SELECT circuit_state, consecutive_failures, circuit_open_count, circuit_opened_at,
                               circuit_retry_at, half_open_probe_id
                        FROM endpoints WHERE id = :id FOR UPDATE
                        """)
                .param("id", endpointId)
                .query((rs, n) -> new State(CircuitState.valueOf(rs.getString("circuit_state")),
                        rs.getInt("consecutive_failures"), rs.getInt("circuit_open_count"),
                        instant(rs, "circuit_opened_at"), instant(rs, "circuit_retry_at"),
                        rs.getObject("half_open_probe_id", UUID.class)))
                .optional();
    }

    public void save(UUID endpointId, State state) {
        jdbc.sql("""
                        UPDATE endpoints
                        SET circuit_state = :state, consecutive_failures = :failures, circuit_open_count = :openCount,
                            circuit_opened_at = :openedAt, circuit_retry_at = :retryAt, half_open_probe_id = :probe
                        WHERE id = :id
                        """)
                .param("state", state.state().name())
                .param("failures", state.consecutiveFailures())
                .param("openCount", state.openCount())
                .param("openedAt", state.openedAt() == null ? null : offset(state.openedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("retryAt", state.retryAt() == null ? null : offset(state.retryAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("probe", state.probeDeliveryId(), Types.OTHER)
                .param("id", endpointId)
                .update();
    }

    /** Closes the circuit by hand. Returns false if the endpoint does not exist for this account. */
    public boolean reset(UUID accountId, UUID endpointId) {
        return jdbc.sql("""
                        UPDATE endpoints
                        SET circuit_state = 'CLOSED', consecutive_failures = 0, circuit_open_count = 0,
                            circuit_opened_at = NULL, circuit_retry_at = NULL, half_open_probe_id = NULL
                        WHERE id = :id AND account_id = :account AND deleted_at IS NULL
                        """)
                .param("id", endpointId)
                .param("account", accountId)
                .update() == 1;
    }

    public record Probe(UUID endpointId, UUID deliveryId, CircuitState previous) {}

    /**
     * Moves circuits whose cool-down has passed to HALF_OPEN and picks exactly one probe: the
     * endpoint's earliest due delivery. Also re-picks a probe for HALF_OPEN circuits whose probe
     * finished without deciding (dead-lettered after a 429, cancelled, endpoint re-enabled...), so a
     * circuit can never stay stuck. Circuits with nothing due wait until something is.
     */
    public List<Probe> startProbes(int limit, Instant now) {
        return jdbc.sql("""
                        WITH ready AS (
                            SELECT e.id, e.circuit_state AS previous FROM endpoints e
                            WHERE e.deleted_at IS NULL
                              AND ((e.circuit_state = 'OPEN' AND e.circuit_retry_at <= :now)
                                OR (e.circuit_state = 'HALF_OPEN' AND NOT EXISTS (
                                        SELECT 1 FROM deliveries p WHERE p.id = e.half_open_probe_id
                                          AND p.status IN ('PENDING', 'RETRYING', 'IN_FLIGHT'))))
                              -- Only circuits with something to probe count against the limit; idle ones
                              -- would otherwise fill every batch and starve endpoints that have work.
                              AND EXISTS (SELECT 1 FROM deliveries d WHERE d.endpoint_id = e.id
                                          AND d.status IN ('PENDING', 'RETRYING') AND d.next_attempt_at <= :now)
                            LIMIT :limit
                            FOR UPDATE OF e SKIP LOCKED
                        ), probes AS (
                            SELECT r.id AS endpoint_id, r.previous,
                                   (SELECT d.id FROM deliveries d
                                    WHERE d.endpoint_id = r.id AND d.status IN ('PENDING', 'RETRYING')
                                      AND d.next_attempt_at <= :now
                                    ORDER BY d.next_attempt_at, d.id LIMIT 1) AS delivery_id
                            FROM ready r
                        )
                        UPDATE endpoints e
                        SET circuit_state = 'HALF_OPEN', half_open_probe_id = p.delivery_id
                        FROM probes p
                        WHERE e.id = p.endpoint_id AND p.delivery_id IS NOT NULL
                        RETURNING e.id, p.delivery_id, p.previous
                        """)
                .param("now", offset(now))
                .param("limit", limit)
                .query((rs, n) -> new Probe(rs.getObject("id", UUID.class), rs.getObject("delivery_id", UUID.class),
                        CircuitState.valueOf(rs.getString("previous"))))
                .list();
    }
}
