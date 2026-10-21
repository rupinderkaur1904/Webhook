package dev.webhooks.platform.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.webhooks.platform.WebhookPlatformApplication;
import dev.webhooks.platform.crypto.Tokens;
import dev.webhooks.platform.endpoint.EndpointDtos.CreateEndpointRequest;
import dev.webhooks.platform.endpoint.EndpointService;
import dev.webhooks.platform.support.PostgresInitializer;
import dev.webhooks.platform.support.TestReceiver;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts the real application, lets the worker fail an attempt and schedule a retry, shuts the
 * application down, starts a fresh instance, and checks that the new instance completes the
 * delivery. Proves retry state lives in PostgreSQL rather than in memory.
 */
class ScheduledRetrySurvivesRestartTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(WebhookPlatformApplication.class)
                .profiles("test")
                .initializers(new PostgresInitializer())
                // Command-line arguments: highest precedence, so they override application-test.yml.
                // The first retry lands 2-4s after the failure: long enough to shut the first
                // instance down before it is due, short enough to keep the test quick.
                .run("--server.port=0", "--webhooks.worker.enabled=true", "--webhooks.worker.poll-interval=100ms",
                        "--webhooks.retry.base-delay=4s", "--webhooks.retry.max-delay=4s",
                        "--webhooks.retry.max-attempts=5");
    }

    private JsonNode call(int port, String method, String path, String apiKey, String body, String idempotencyKey)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return json.readTree(http.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    @Test
    void retryScheduledBeforeShutdownIsCompletedAfterRestart() throws Exception {
        try (TestReceiver receiver = new TestReceiver()) {
            AtomicInteger calls = new AtomicInteger();
            receiver.respondWith(r -> TestReceiver.Response.status(calls.incrementAndGet() == 1 ? 503 : 200));

            String apiKey = Tokens.newApiKey();
            String deliveryId;
            try (ConfigurableApplicationContext first = start()) {
                JdbcClient jdbc = first.getBean(JdbcClient.class);
                UUID account = UUID.randomUUID();
                jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (:id, :e, '{noop}x')")
                        .param("id", account).param("e", account + "@restart.test").update();
                jdbc.sql("INSERT INTO api_keys (account_id, name, key_prefix, key_hash) VALUES (:a, 'k', 'whk_', :h)")
                        .param("a", account).param("h", Tokens.sha256(apiKey)).update();
                int port = first.getEnvironment().getProperty("local.server.port", Integer.class);

                // Endpoint management needs a dashboard session; the service is called directly instead.
                String endpointId = first.getBean(EndpointService.class)
                        .create(account, new CreateEndpointRequest("restart", null, receiver.url("/hook"), null))
                        .endpoint().id().toString();
                // Shut the first instance down as soon as the failure is recorded and the retry scheduled.
                deliveryId = call(port, "POST", "/api/v1/events", apiKey,
                        "{\"eventType\":\"a.b\",\"payload\":{},\"endpointIds\":[\"" + endpointId + "\"]}", "restart-1")
                        .get("deliveries").get(0).get("id").asString();
                await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).until(() -> calls.get() >= 1 && "RETRYING".equals(
                        jdbc.sql("SELECT status FROM deliveries WHERE id = :id").param("id", UUID.fromString(deliveryId))
                                .query(String.class).single()));
            }
            int callsBeforeRestart = calls.get();
            assertThat(callsBeforeRestart).isEqualTo(1);

            try (ConfigurableApplicationContext second = start()) {
                int port = second.getEnvironment().getProperty("local.server.port", Integer.class);
                await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    JsonNode detail = call(port, "GET", "/api/v1/deliveries/" + deliveryId, apiKey, null, null);
                    assertThat(detail.get("delivery").get("status").asString()).isEqualTo("DELIVERED");
                    assertThat(detail.get("attempts")).extracting(a -> a.get("responseStatus").asInt())
                            .containsExactly(503, 200);
                });
            }
        }
    }
}
