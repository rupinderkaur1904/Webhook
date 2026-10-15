package dev.webhooks.platform.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.webhooks.platform.WebhookPlatformApplication;
import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.PostgresInitializer;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import dev.webhooks.platform.support.TestReceiver;
import dev.webhooks.platform.support.TestReceiver.Response;
import dev.webhooks.platform.worker.LeaseReaper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Stopping an instance while a request is in flight (docs/PLAN.md §7.2): the worker stops claiming,
 * waits up to {@code shutdown-timeout} for in-flight sends to finish and be recorded, and leaves
 * anything still unfinished to the lease reaper. Either way, nothing is lost.
 */
@IntegrationTest
class GracefulShutdownTest {

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired LeaseReaper reaper;

    Account account;

    @BeforeEach
    void setUp() {
        data.clearDeliveries();
        account = data.newAccount();
    }

    UUID submit(TestReceiver receiver, String key) throws Exception {
        String endpointId = json.readTree(mvc.perform(post("/api/v1/endpoints").header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + key + "\",\"url\":\"" + receiver.url("/hook") + "\"}"))
                .andReturn().getResponse().getContentAsString()).get("endpoint").get("id").asString();
        return UUID.fromString(json.readTree(mvc.perform(post("/api/v1/events").header("Authorization", account.bearer())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"order.created\",\"payload\":{},\"endpointIds\":[\"" + endpointId + "\"]}"))
                .andReturn().getResponse().getContentAsString()).get("deliveries").get(0).get("id").asString());
    }

    ConfigurableApplicationContext startWorkerInstance(String shutdownTimeout) {
        List<String> args = new ArrayList<>(List.of("--server.port=0", "--webhooks.worker.enabled=true",
                "--webhooks.worker.poll-interval=50ms", "--webhooks.worker.shutdown-timeout=" + shutdownTimeout,
                "--webhooks.worker.reaper-interval=1h"));
        return new SpringApplicationBuilder(WebhookPlatformApplication.class).profiles("test")
                .initializers(new PostgresInitializer()).run(args.toArray(String[]::new));
    }

    String status(UUID id) {
        return jdbc.sql("SELECT status FROM deliveries WHERE id = :id").param("id", id).query(String.class).single();
    }

    @Test
    void inFlightRequestIsFinishedAndRecordedBeforeShutdownCompletes() throws Exception {
        try (TestReceiver receiver = new TestReceiver()) {
            CountDownLatch received = new CountDownLatch(1);
            receiver.respondWith(r -> {
                received.countDown();
                return new Response(200, java.util.Map.of(), "{}", 1200);
            });
            UUID id = submit(receiver, "graceful");
            ConfigurableApplicationContext instance = startWorkerInstance("10s");
            assertThat(received.await(30, TimeUnit.SECONDS)).isTrue();
            instance.close(); // returns only after the worker has drained
            assertThat(status(id)).isEqualTo("DELIVERED");
            assertThat(receiver.requests()).hasSize(1);
        }
    }

    @Test
    void requestStillRunningAtTheShutdownDeadlineIsNotLost() throws Exception {
        try (TestReceiver receiver = new TestReceiver()) {
            CountDownLatch received = new CountDownLatch(1);
            receiver.respondWith(r -> {
                received.countDown();
                return new Response(200, java.util.Map.of(), "{}", 1800);
            });
            UUID id = submit(receiver, "deadline");
            ConfigurableApplicationContext instance = startWorkerInstance("100ms");
            assertThat(received.await(30, TimeUnit.SECONDS)).isTrue();
            instance.close();

            // The interrupted attempt is either recorded as a failure and scheduled for retry, or left
            // in flight for the reaper. It must not be delivered-and-forgotten or stuck.
            String after = status(id);
            assertThat(after).isIn("RETRYING", "IN_FLIGHT");
            if (after.equals("IN_FLIGHT")) {
                jdbc.sql("UPDATE deliveries SET lease_expires_at = now() - interval '1 second' WHERE id = :id")
                        .param("id", id).update();
                assertThat(reaper.reapExpired()).isEqualTo(1);
            }
            assertThat(status(id)).isEqualTo("RETRYING");
        }
    }
}
