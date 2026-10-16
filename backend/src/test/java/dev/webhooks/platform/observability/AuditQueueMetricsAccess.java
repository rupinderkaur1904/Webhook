package dev.webhooks.platform.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Test access to {@link QueueMetrics}' package-private snapshot, for running its query on another database. */
public final class AuditQueueMetricsAccess {

    private final QueueMetrics metrics;

    public AuditQueueMetricsAccess(JdbcClient jdbc) {
        this.metrics = new QueueMetrics(jdbc, new SimpleMeterRegistry());
    }

    public Map<String, Double> read() {
        return metrics.values();
    }
}
