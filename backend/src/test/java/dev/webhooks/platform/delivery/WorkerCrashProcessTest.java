package dev.webhooks.platform.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.webhooks.platform.WebhookPlatformApplication;
import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import dev.webhooks.platform.support.TestReceiver;
import dev.webhooks.platform.support.TestReceiver.Response;
import dev.webhooks.platform.worker.DeliveryProcessor;
import dev.webhooks.platform.worker.LeaseReaper;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A real crash: the platform runs as a separate JVM with its worker enabled, starts sending a
 * delivery, and is killed with SIGKILL while the request is in flight. No shutdown hook runs, so the
 * delivery is left IN_FLIGHT with a STARTED attempt, exactly as after a power loss. After the lease
 * expires, the reaper recovers it and a healthy worker delivers it.
 */
@IntegrationTest
class WorkerCrashProcessTest {

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired Environment env;
    @Autowired DeliveryRepository deliveries;
    @Autowired DeliveryProcessor processor;
    @Autowired LeaseReaper reaper;

    @Test
    void deliveryInFlightWhenTheProcessIsKilledIsRecoveredAndDelivered() throws Exception {
        data.clearDeliveries();
        Account account = data.newAccount();
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Process child = null;
        try (TestReceiver receiver = new TestReceiver()) {
            receiver.respondWith(r -> {
                if (received.getCount() > 0) {
                    received.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS); // hold the first request open until the kill
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return Response.status(200);
            });
            String endpointId = json.readTree(mvc.perform(post("/api/v1/endpoints").header("Authorization", account.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"crash\",\"url\":\"" + receiver.url("/hook") + "\"}"))
                    .andReturn().getResponse().getContentAsString()).get("endpoint").get("id").asString();
            // This test context's worker is disabled, so the delivery waits for the child process.
            UUID deliveryId = UUID.fromString(json.readTree(mvc.perform(post("/api/v1/events")
                            .header("Authorization", account.bearer()).header("Idempotency-Key", "crash-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"eventType\":\"order.created\",\"payload\":{},\"endpointIds\":[\"" + endpointId + "\"]}"))
                    .andReturn().getResponse().getContentAsString()).get("deliveries").get(0).get("id").asString());

            child = startPlatformProcess();

            assertThat(received.await(60, TimeUnit.SECONDS)).as("child process sent the request").isTrue();
            child.destroyForcibly(); // SIGKILL: no graceful shutdown, no result recorded
            assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();

            var state = jdbc.sql("SELECT status, lease_owner FROM deliveries WHERE id = :id").param("id", deliveryId)
                    .query((rs, n) -> List.of(rs.getString(1), rs.getString(2))).single();
            assertThat(state.getFirst()).isEqualTo("IN_FLIGHT");
            assertThat(jdbc.sql("SELECT status FROM delivery_attempts WHERE delivery_id = :id").param("id", deliveryId)
                    .query(String.class).single()).isEqualTo("STARTED");

            // The child used an 8s lease; wait until it has expired, then recover.
            await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250))
                    .until(() -> reaper.reapExpired() == 1);
            assertThat(jdbc.sql("SELECT status FROM deliveries WHERE id = :id").param("id", deliveryId)
                    .query(String.class).single()).isEqualTo("RETRYING");

            jdbc.sql("UPDATE deliveries SET next_attempt_at = now() WHERE id = :id").param("id", deliveryId).update();
            deliveries.claimDue(10, "healthy-worker", Duration.ofSeconds(60)).forEach(processor::process);

            assertThat(jdbc.sql("SELECT status FROM deliveries WHERE id = :id").param("id", deliveryId)
                    .query(String.class).single()).isEqualTo("DELIVERED");
            assertThat(jdbc.sql("SELECT status || ':' || outcome FROM delivery_attempts WHERE delivery_id = :id ORDER BY attempt_number")
                    .param("id", deliveryId).query(String.class).list())
                    .containsExactly("ABANDONED:LEASE_EXPIRED", "SUCCEEDED:SUCCESS");
            assertThat(receiver.requests()).hasSize(2);
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
            }
            release.countDown();
        }
    }

    private Process startPlatformProcess() throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> command = new ArrayList<>(List.of(java, "-cp", System.getProperty("java.class.path"),
                WebhookPlatformApplication.class.getName(),
                "--spring.profiles.active=test",
                "--spring.datasource.url=" + env.getProperty("spring.datasource.url"),
                "--spring.datasource.username=" + env.getProperty("spring.datasource.username"),
                "--spring.datasource.password=" + env.getProperty("spring.datasource.password"),
                "--server.port=0",
                "--webhooks.worker.enabled=true",
                "--webhooks.worker.poll-interval=100ms",
                "--webhooks.worker.lease-duration=8s",
                // The child must not recover its own work: only the parent's reaper does, after the kill.
                "--webhooks.worker.reaper-interval=1h"));
        Path log = Files.createTempFile("crashed-platform", ".log");
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).directory(new File(".")).start();
    }
}
