package dev.webhooks.platform.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.TestData;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Real PostgreSQL row locking: concurrent claimers must receive disjoint sets of deliveries. */
@IntegrationTest
class ClaimConcurrencyTest {

    @Autowired JdbcClient jdbc;
    @Autowired TestData data;
    @Autowired DeliveryRepository deliveries;

    @Test
    void concurrentClaimersNeverReceiveTheSameDelivery() throws Exception {
        data.clearDeliveries();
        var account = data.newAccount();
        UUID endpoint = jdbc.sql("""
                        INSERT INTO endpoints (account_id, name, url, secret_ciphertext, secret_prefix)
                        VALUES (:a, 'bulk', 'http://127.0.0.1:9/', '\\x00', 'whsec_') RETURNING id
                        """).param("a", account.id()).query(UUID.class).single();
        int total = 400;
        for (int i = 0; i < total; i++) {
            UUID event = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO events (id, account_id, event_type, idempotency_key, request_hash, body, created_at)
                            VALUES (:id, :a, 'bulk.test', :key, '\\x00', '{}', now())
                            """).param("id", event).param("a", account.id()).param("key", "bulk-" + i).update();
            deliveries.insertPending(UUID.randomUUID(), account.id(), event, endpoint, 1);
        }

        int workers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<List<UUID>>> futures = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            String workerId = "worker-" + w;
            futures.add(pool.submit(() -> {
                start.await();
                List<UUID> mine = new ArrayList<>();
                List<ClaimedDelivery> batch;
                do {
                    batch = deliveries.claimDue(7, workerId, Duration.ofSeconds(60));
                    batch.forEach(c -> mine.add(c.deliveryId()));
                } while (!batch.isEmpty());
                return mine;
            }));
        }
        start.countDown();

        List<UUID> all = new ArrayList<>();
        for (Future<List<UUID>> f : futures) {
            all.addAll(f.get());
        }
        pool.shutdown();

        Set<UUID> unique = new HashSet<>(all);
        assertThat(all).hasSize(total);
        assertThat(unique).hasSize(total);
        assertThat(jdbc.sql("SELECT count(*) FROM deliveries WHERE status = 'IN_FLIGHT' AND attempt_count = 1")
                .query(Integer.class).single()).isEqualTo(total);
        assertThat(jdbc.sql("SELECT count(*) FROM delivery_attempts a JOIN deliveries d ON d.id = a.delivery_id WHERE d.endpoint_id = :e")
                .param("e", endpoint).query(Integer.class).single()).isEqualTo(total);
    }
}
