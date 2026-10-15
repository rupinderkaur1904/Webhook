package dev.webhooks.platform.metrics;

import static dev.webhooks.platform.common.Jdbc.instant;
import static dev.webhooks.platform.common.Jdbc.integer;

import dev.webhooks.platform.metrics.MetricsDtos.Attempts;
import dev.webhooks.platform.metrics.MetricsDtos.Current;
import dev.webhooks.platform.metrics.MetricsDtos.DeadLettered;
import dev.webhooks.platform.metrics.MetricsDtos.Overview;
import dev.webhooks.platform.metrics.MetricsDtos.RecentAttempt;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Overview metrics, computed from the system of record with the definitions in docs/PLAN.md §5.5.
 * All window boundaries use the database clock.
 */
@Repository
public class MetricsRepository {

    static final int RECENT_ACTIVITY = 20;

    private final JdbcClient jdbc;

    public MetricsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Runs in one read-only transaction so every number is taken from the same snapshot. */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Overview overview(UUID accountId, String windowName, Duration window) {
        record Totals(Instant start, Instant end, long events, long succeeded) {}
        Totals totals = jdbc.sql("""
                        WITH w AS (SELECT now() AS window_end, now() - make_interval(secs => :secs) AS window_start)
                        SELECT w.window_start, w.window_end,
                               (SELECT count(*) FROM events
                                WHERE account_id = :account AND created_at >= w.window_start) AS events,
                               (SELECT count(*) FROM deliveries
                                WHERE account_id = :account AND delivered_at >= w.window_start) AS succeeded
                        FROM w
                        """)
                .param("secs", (double) window.toSeconds())
                .param("account", accountId)
                .query((rs, n) -> new Totals(instant(rs, "window_start"), instant(rs, "window_end"),
                        rs.getLong("events"), rs.getLong("succeeded")))
                .single();

        Map<String, Long> byReason = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT dead_letter_reason, count(*) AS n FROM deliveries
                        WHERE account_id = :account AND dead_lettered_at >= now() - make_interval(secs => :secs)
                        GROUP BY dead_letter_reason ORDER BY dead_letter_reason
                        """)
                .param("secs", (double) window.toSeconds())
                .param("account", accountId)
                .query(rs -> {
                    byReason.put(rs.getString("dead_letter_reason"), rs.getLong("n"));
                });
        long deadLettered = byReason.values().stream().mapToLong(Long::longValue).sum();

        Map<String, Long> current = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT status, count(*) AS n FROM deliveries
                        WHERE account_id = :account AND status IN ('PENDING', 'RETRYING', 'IN_FLIGHT')
                        GROUP BY status
                        """)
                .param("account", accountId)
                .query(rs -> {
                    current.put(rs.getString("status"), rs.getLong("n"));
                });

        Attempts attempts = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded,
                               count(*) FILTER (WHERE status IN ('FAILED', 'ABANDONED')) AS failed,
                               percentile_disc(0.5) WITHIN GROUP (ORDER BY duration_ms) AS p50,
                               percentile_disc(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95
                        FROM delivery_attempts
                        WHERE account_id = :account AND started_at >= now() - make_interval(secs => :secs)
                          AND status <> 'STARTED'
                        """)
                .param("secs", (double) window.toSeconds())
                .param("account", accountId)
                .query((rs, n) -> {
                    long ok = rs.getLong("succeeded");
                    long failed = rs.getLong("failed");
                    return new Attempts(ok, failed, rate(ok, ok + failed), integer(rs, "p50"), integer(rs, "p95"));
                })
                .single();

        List<RecentAttempt> recent = jdbc.sql("""
                        SELECT a.id, a.delivery_id, a.endpoint_id, e.name AS endpoint_name, ev.event_type,
                               a.attempt_number, a.status, a.outcome, a.response_status, a.duration_ms, a.started_at
                        FROM delivery_attempts a
                        JOIN deliveries d ON d.id = a.delivery_id
                        JOIN endpoints e ON e.id = a.endpoint_id
                        JOIN events ev ON ev.id = d.event_id
                        WHERE a.account_id = :account
                        ORDER BY a.started_at DESC, a.id DESC
                        LIMIT :limit
                        """)
                .param("account", accountId)
                .param("limit", RECENT_ACTIVITY)
                .query((rs, n) -> new RecentAttempt(
                        rs.getObject("id", UUID.class),
                        rs.getObject("delivery_id", UUID.class),
                        rs.getObject("endpoint_id", UUID.class),
                        rs.getString("endpoint_name"),
                        rs.getString("event_type"),
                        rs.getInt("attempt_number"),
                        rs.getString("status"),
                        rs.getString("outcome"),
                        integer(rs, "response_status"),
                        integer(rs, "duration_ms"),
                        instant(rs, "started_at")))
                .list();

        return new Overview(windowName, totals.start(), totals.end(), totals.events(), totals.succeeded(),
                new DeadLettered(deadLettered, byReason),
                new Current(current.getOrDefault("PENDING", 0L), current.getOrDefault("RETRYING", 0L),
                        current.getOrDefault("IN_FLIGHT", 0L)),
                rate(totals.succeeded(), totals.succeeded() + deadLettered),
                attempts,
                recent);
    }

    static Double rate(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }
}
