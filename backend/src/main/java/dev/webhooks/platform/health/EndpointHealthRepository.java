package dev.webhooks.platform.health;

import static dev.webhooks.platform.common.Jdbc.instant;
import static dev.webhooks.platform.common.Jdbc.integer;

import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Per-endpoint attempt statistics for health, for all of an account's endpoints in one query. */
@Repository
public class EndpointHealthRepository {

    public record Stats(long succeeded, long failed, Integer p50, Integer p95, long deadLetters, Instant lastSuccessAt,
            Instant lastFailureAt, String lastFailureOutcome, Integer lastFailureStatus, String lastFailureError) {

        public static final Stats EMPTY = new Stats(0, 0, null, null, 0, null, null, null, null, null);

        public long attempts() {
            return succeeded + failed;
        }

        public Double successRate() {
            return attempts() == 0 ? null : (double) succeeded / attempts();
        }
    }

    private final JdbcClient jdbc;

    public EndpointHealthRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stats per endpoint id; {@code endpointId} null means every live endpoint of the account. */
    public Map<UUID, Stats> stats(UUID accountId, UUID endpointId, Duration window) {
        Map<UUID, Stats> result = new HashMap<>();
        jdbc.sql("""
                        SELECT e.id,
                               w.succeeded, w.failed, w.p50, w.p95,
                               (SELECT count(*) FROM deliveries d
                                WHERE d.endpoint_id = e.id
                                  AND d.dead_lettered_at >= now() - make_interval(secs => :secs)) AS dead_letters,
                               (SELECT s.started_at FROM delivery_attempts s
                                WHERE s.endpoint_id = e.id AND s.status = 'SUCCEEDED'
                                ORDER BY s.started_at DESC LIMIT 1) AS last_success_at,
                               f.started_at AS last_failure_at, f.outcome AS last_failure_outcome,
                               f.response_status AS last_failure_status, f.error_message AS last_failure_error
                        FROM endpoints e
                        LEFT JOIN LATERAL (
                            SELECT count(*) FILTER (WHERE a.status = 'SUCCEEDED') AS succeeded,
                                   count(*) FILTER (WHERE a.status IN ('FAILED', 'ABANDONED')) AS failed,
                                   percentile_disc(0.5) WITHIN GROUP (ORDER BY a.duration_ms) AS p50,
                                   percentile_disc(0.95) WITHIN GROUP (ORDER BY a.duration_ms) AS p95
                            FROM delivery_attempts a
                            WHERE a.endpoint_id = e.id AND a.status <> 'STARTED'
                              AND a.started_at >= now() - make_interval(secs => :secs)
                        ) w ON true
                        LEFT JOIN LATERAL (
                            SELECT a.started_at, a.outcome, a.response_status, a.error_message
                            FROM delivery_attempts a
                            WHERE a.endpoint_id = e.id AND a.status IN ('FAILED', 'ABANDONED')
                            ORDER BY a.started_at DESC LIMIT 1
                        ) f ON true
                        WHERE e.account_id = :account AND e.deleted_at IS NULL
                          AND (CAST(:endpointId AS uuid) IS NULL OR e.id = :endpointId)
                        """)
                .param("secs", (double) window.toSeconds())
                .param("account", accountId)
                .param("endpointId", endpointId, Types.OTHER)
                .query(rs -> {
                    result.put(rs.getObject("id", UUID.class), new Stats(
                            rs.getLong("succeeded"), rs.getLong("failed"), integer(rs, "p50"), integer(rs, "p95"),
                            rs.getLong("dead_letters"), instant(rs, "last_success_at"), instant(rs, "last_failure_at"),
                            rs.getString("last_failure_outcome"), integer(rs, "last_failure_status"),
                            rs.getString("last_failure_error")));
                });
        return result;
    }
}
