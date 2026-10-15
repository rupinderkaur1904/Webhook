package dev.webhooks.platform.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Queue and circuit gauges across all accounts, read from PostgreSQL (the system of record), so every
 * instance reports the same numbers. One query serves all gauges and is cached for a few seconds, so a
 * scrape costs at most one small query.
 *
 * <ul>
 *   <li>{@code webhooks.queue.deliveries{status}}: PENDING, RETRYING and IN_FLIGHT deliveries.</li>
 *   <li>{@code webhooks.queue.due}: deliveries whose next attempt is due now (backlog).</li>
 *   <li>{@code webhooks.queue.oldest.due.seconds}: how long the oldest due delivery has waited.</li>
 *   <li>{@code webhooks.circuits{state}}: endpoints with an OPEN or HALF_OPEN circuit.</li>
 * </ul>
 */
@Component
public class QueueMetrics {

    static final Duration MAX_AGE = Duration.ofSeconds(5);
    private static final String[] KEYS = {"status:PENDING", "status:RETRYING", "status:IN_FLIGHT", "circuit:OPEN",
            "circuit:HALF_OPEN", "due", "oldestDue"};
    private static final Map<String, Double> UNKNOWN = unknown();
    private static final Logger log = LoggerFactory.getLogger(QueueMetrics.class);

    private final JdbcClient jdbc;
    private volatile Snapshot snapshot;

    record Snapshot(long takenAtNanos, Map<String, Double> values) {}

    public QueueMetrics(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        for (String status : new String[] {"PENDING", "RETRYING", "IN_FLIGHT"}) {
            gauge(meters, "webhooks.queue.deliveries", "status", status, v -> v.get("status:" + status));
        }
        for (String state : new String[] {"OPEN", "HALF_OPEN"}) {
            gauge(meters, "webhooks.circuits", "state", state, v -> v.get("circuit:" + state));
        }
        Gauge.builder("webhooks.queue.due", this, m -> m.value("due")).description("Deliveries due now").register(meters);
        Gauge.builder("webhooks.queue.oldest.due.seconds", this, m -> m.value("oldestDue"))
                .description("Age of the oldest due delivery").baseUnit("seconds").register(meters);
    }

    private void gauge(MeterRegistry meters, String name, String tag, String value,
            ToDoubleFunction<Map<String, Double>> read) {
        Gauge.builder(name, this, m -> read.applyAsDouble(m.values())).tag(tag, value).register(meters);
    }

    private static Map<String, Double> unknown() {
        Map<String, Double> values = new HashMap<>();
        for (String key : KEYS) {
            values.put(key, Double.NaN);
        }
        return Map.copyOf(values);
    }

    private double value(String key) {
        return values().get(key);
    }

    Map<String, Double> values() {
        Snapshot current = snapshot;
        if (current == null || System.nanoTime() - current.takenAtNanos() > MAX_AGE.toNanos()) {
            try {
                current = new Snapshot(System.nanoTime(), query());
            } catch (RuntimeException e) {
                // A scrape must never fail because the database is briefly unreachable: report "unknown"
                // (NaN, which Prometheus drops from aggregations) and try again on the next scrape.
                log.warn("Could not read queue metrics: {}", e.toString());
                current = new Snapshot(System.nanoTime() - MAX_AGE.toNanos(), UNKNOWN);
            }
            snapshot = current;
        }
        return current.values();
    }

    private Map<String, Double> query() {
        Map<String, Double> values = new HashMap<>();
        for (String key : KEYS) {
            values.put(key, 0.0);
        }
        jdbc.sql("""
                        SELECT 'status:' || status AS key, count(*)::float8 AS n FROM deliveries
                        WHERE status IN ('PENDING', 'RETRYING', 'IN_FLIGHT') GROUP BY status
                        UNION ALL
                        SELECT 'circuit:' || circuit_state, count(*)::float8 FROM endpoints
                        WHERE circuit_state <> 'CLOSED' AND deleted_at IS NULL GROUP BY circuit_state
                        UNION ALL
                        SELECT 'due', count(*)::float8 FROM deliveries
                        WHERE status IN ('PENDING', 'RETRYING') AND next_attempt_at <= now()
                        UNION ALL
                        SELECT 'oldestDue', COALESCE(EXTRACT(EPOCH FROM now() - min(next_attempt_at)), 0)::float8
                        FROM deliveries WHERE status IN ('PENDING', 'RETRYING') AND next_attempt_at <= now()
                        """)
                .query(rs -> {
                    values.put(rs.getString("key"), rs.getDouble("n"));
                });
        return values;
    }
}
