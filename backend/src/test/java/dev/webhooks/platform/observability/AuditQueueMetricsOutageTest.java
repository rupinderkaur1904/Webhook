package dev.webhooks.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * While PostgreSQL is unreachable a scrape must stay cheap: each attempt to read the gauges waits up to
 * the pool's connection timeout (30 s by default), so the seven queue and circuit gauges must share one
 * attempt per scrape instead of each retrying the query.
 */
class AuditQueueMetricsOutageTest {

    @Test
    void oneScrapeDuringAnOutageTriesTheDatabaseOnceAndReportsNaN() {
        AtomicInteger connectionAttempts = new AtomicInteger();
        DataSource down = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        connectionAttempts.incrementAndGet();
                        throw new SQLException("Connection refused", "08001");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new QueueMetrics(JdbcClient.create(down), registry);

        List<Double> values = scrape(registry);

        assertThat(values).hasSize(7).allSatisfy(v -> assertThat(v).isNaN());
        assertThat(connectionAttempts.get()).as("database round trips for one scrape of 7 gauges").isEqualTo(1);
    }

    private static List<Double> scrape(SimpleMeterRegistry registry) {
        List<Double> values = new ArrayList<>();
        for (Meter meter : registry.getMeters()) {
            for (Measurement m : meter.measure()) {
                values.add(m.getValue());
            }
        }
        return values;
    }
}
