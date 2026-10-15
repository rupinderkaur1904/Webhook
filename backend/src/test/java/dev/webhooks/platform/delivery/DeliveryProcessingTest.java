package dev.webhooks.platform.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import dev.webhooks.platform.support.TestReceiver;
import dev.webhooks.platform.support.TestReceiver.Response;
import dev.webhooks.platform.worker.DeliveryProcessor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives claim + process directly (worker thread disabled) against a real HTTP receiver, so each
 * test controls exactly when an attempt happens.
 */
@IntegrationTest
class DeliveryProcessingTest {

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired DeliveryRepository deliveries;
    @Autowired DeliveryProcessor processor;

    TestReceiver receiver;
    Account account;
    String endpointId;
    String secret;

    @BeforeEach
    void setUp() throws Exception {
        data.clearDeliveries();
        receiver = new TestReceiver();
        account = data.newAccount();
        JsonNode created = json.readTree(mvc.perform(post("/api/v1/endpoints").header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"rx\",\"url\":\"" + receiver.url("/hook") + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        endpointId = created.get("endpoint").get("id").asString();
        secret = created.get("secret").asString();
    }

    @AfterEach
    void tearDown() {
        receiver.close();
    }

    UUID submit(String key) throws Exception {
        String body = mvc.perform(post("/api/v1/events").header("Authorization", account.bearer())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"order.created\",\"payload\":{\"orderId\":\"" + key + "\"}}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("deliveries").get(0).get("id").asString());
    }

    void runOneAttempt() {
        List<ClaimedDelivery> claimed = deliveries.claimDue(10, "test-worker", Duration.ofSeconds(60));
        assertThat(claimed).hasSize(1);
        processor.process(claimed.getFirst());
    }

    JsonNode detail(UUID deliveryId) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/deliveries/" + deliveryId).header("Authorization", account.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void successfulDeliveryIsSignedAndRecorded() throws Exception {
        UUID deliveryId = submit("ok");
        assertThat(receiver.requests()).isEmpty(); // ingestion never contacts the destination

        runOneAttempt();

        assertThat(receiver.requests()).hasSize(1);
        TestReceiver.Request request = receiver.requests().getFirst();
        JsonNode detail = detail(deliveryId);
        String eventId = detail.get("delivery").get("eventId").asString();

        assertThat(request.header("webhook-id")).isEqualTo(eventId);
        assertThat(request.header("X-Webhook-Delivery-Id")).isEqualTo(deliveryId.toString());
        assertThat(request.header("X-Webhook-Attempt")).isEqualTo("1");
        assertThat(request.header("Content-Type")).startsWith("application/json");
        assertThat(json.readTree(request.bodyText()).get("data").get("orderId").asString()).isEqualTo("ok");
        long timestamp = Long.parseLong(request.header("webhook-timestamp"));
        assertThat(Math.abs(Instant.now().getEpochSecond() - timestamp)).isLessThan(30);
        assertThat(verifySignature(secret, request)).as("independent HMAC verification").isTrue();

        assertThat(detail.get("delivery").get("status").asString()).isEqualTo("DELIVERED");
        assertThat(detail.get("delivery").get("attemptCount").asInt()).isEqualTo(1);
        assertThat(detail.get("delivery").get("lastResponseStatus").asInt()).isEqualTo(200);
        assertThat(detail.get("delivery").get("deliveredAt").isNull()).isFalse();
        JsonNode attempt = detail.get("attempts").get(0);
        assertThat(attempt.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(attempt.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(attempt.get("responseStatus").asInt()).isEqualTo(200);
        assertThat(attempt.get("durationMs").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(attempt.get("finishedAt").isNull()).isFalse();
        assertThat(attempt.get("requestUrl").asString()).isEqualTo(receiver.url("/hook"));
        assertThat(attempt.get("responseBodyExcerpt").asString()).isEqualTo("{\"received\":true}");

        // The lease is released and nothing remains claimable.
        assertThat(jdbc.sql("SELECT lease_token IS NULL FROM deliveries WHERE id = :id").param("id", deliveryId)
                .query(Boolean.class).single()).isTrue();
        assertThat(deliveries.claimDue(10, "test-worker", Duration.ofSeconds(60))).isEmpty();
    }

    @Test
    void serverErrorIsRecordedWithResponseDetailsAndScheduledForRetry() throws Exception {
        receiver.respondWith(r -> new Response(503, Map.of("Retry-After", "30", "X-Internal", "secret"),
                "maintenance\u0000 window", 0));
        UUID deliveryId = submit("err");
        Instant before = Instant.now();
        runOneAttempt();

        JsonNode detail = detail(deliveryId);
        assertThat(detail.get("delivery").get("status").asString()).isEqualTo("RETRYING");
        assertThat(detail.get("delivery").get("lastResponseStatus").asInt()).isEqualTo(503);
        assertThat(detail.get("delivery").get("lastError").asString()).isEqualTo("HTTP 503");
        // 503 + Retry-After: 30 outweighs the 5-10s backoff of the first retry.
        Instant next = Instant.parse(detail.get("delivery").get("nextAttemptAt").asString());
        assertThat(next).isBetween(before.plusSeconds(29), Instant.now().plusSeconds(31));
        JsonNode attempt = detail.get("attempts").get(0);
        assertThat(attempt.get("status").asString()).isEqualTo("FAILED");
        assertThat(attempt.get("outcome").asString()).isEqualTo("HTTP_SERVER_ERROR");
        assertThat(attempt.get("retryable").asBoolean()).isTrue();
        assertThat(attempt.get("retryAfterSeconds").asInt()).isEqualTo(30);
        assertThat(attempt.get("responseHeaders").get("retry-after").asString()).isEqualTo("30");
        assertThat(attempt.get("responseHeaders").has("x-internal")).as("only allowlisted headers stored").isFalse();
        assertThat(attempt.get("responseBodyExcerpt").asString()).isEqualTo("maintenance window");
    }

    @Test
    void redirectsAreNotFollowed() throws Exception {
        receiver.respondWith(r -> r.path().equals("/hook")
                ? new Response(302, Map.of("Location", receiver.url("/elsewhere")), "", 0)
                : Response.status(200));
        UUID deliveryId = submit("redirect");
        runOneAttempt();

        assertThat(receiver.requests()).extracting(TestReceiver.Request::path).containsExactly("/hook");
        assertThat(detail(deliveryId).get("attempts").get(0).get("outcome").asString()).isEqualTo("HTTP_REDIRECT");
    }

    @Test
    void slowReceiverTimesOut() throws Exception {
        receiver.respondWith(r -> new Response(200, Map.of(), "late", 4_000)); // response timeout is 2s in tests
        UUID deliveryId = submit("slow");
        long start = System.nanoTime();
        runOneAttempt();
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        JsonNode attempt = detail(deliveryId).get("attempts").get(0);
        assertThat(attempt.get("outcome").asString()).isEqualTo("TIMEOUT");
        assertThat(attempt.get("responseStatus").isNull()).isTrue();
        assertThat(elapsedMs).isLessThan(3_500);
    }

    @Test
    void connectionRefusedIsRecorded() throws Exception {
        int port = receiver.port();
        receiver.close(); // nothing listens on the port any more
        UUID deliveryId = submit("refused");
        runOneAttempt();

        JsonNode attempt = detail(deliveryId).get("attempts").get(0);
        assertThat(attempt.get("outcome").asString()).isEqualTo("CONNECTION_ERROR");
        assertThat(attempt.get("errorMessage").asString()).doesNotContain("127.0.0.1").doesNotContain(String.valueOf(port));
    }

    @Test
    void destinationResolvingToLoopbackIsBlockedAtSendTime() throws Exception {
        // Simulates a hostname whose DNS answer changed to a private address after registration:
        // "localhost" is not allowlisted (only 127.0.0.1 is) and resolves to loopback.
        UUID deliveryId = submit("rebind");
        jdbc.sql("UPDATE endpoints SET url = :url WHERE id = :id")
                .param("url", "https://localhost:" + receiver.port() + "/hook")
                .param("id", UUID.fromString(endpointId)).update();
        runOneAttempt();

        assertThat(receiver.requests()).isEmpty();
        JsonNode detail = detail(deliveryId);
        assertThat(detail.get("attempts").get(0).get("outcome").asString()).isEqualTo("DESTINATION_BLOCKED");
        assertThat(detail.get("delivery").get("deadLetterReason").asString()).isEqualTo("DESTINATION_BLOCKED");
    }

    @Test
    void staleWorkerCannotOverwriteNewerState() throws Exception {
        UUID deliveryId = submit("stale");
        List<ClaimedDelivery> claimed = deliveries.claimDue(10, "slow-worker", Duration.ofSeconds(60));
        // Another worker took over after this worker's lease expired.
        jdbc.sql("UPDATE deliveries SET lease_token = gen_random_uuid() WHERE id = :id").param("id", deliveryId).update();

        processor.process(claimed.getFirst());

        JsonNode detail = detail(deliveryId);
        assertThat(detail.get("delivery").get("status").asString()).isEqualTo("IN_FLIGHT");
        // The stale worker's observation is still kept in the attempt history.
        assertThat(detail.get("attempts").get(0).get("status").asString()).isEqualTo("SUCCEEDED");
    }

    @Test
    void disabledEndpointsAreNotClaimedUntilReenabled() throws Exception {
        submit("paused");
        jdbc.sql("UPDATE endpoints SET enabled = false WHERE id = :id").param("id", UUID.fromString(endpointId)).update();
        assertThat(deliveries.claimDue(10, "w", Duration.ofSeconds(60))).isEmpty();
        jdbc.sql("UPDATE endpoints SET enabled = true WHERE id = :id").param("id", UUID.fromString(endpointId)).update();
        assertThat(deliveries.claimDue(10, "w", Duration.ofSeconds(60))).hasSize(1);
    }

    @Test
    void deliveryListFiltersAndIsScopedToAccount() throws Exception {
        UUID ok = submit("f-ok");
        runOneAttempt();
        receiver.respondWith(r -> Response.status(400));
        UUID failed = submit("f-fail");
        runOneAttempt();

        mvc.perform(get("/api/v1/deliveries?status=DEAD_LETTERED").header("Authorization", account.bearer()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(failed.toString()))
                .andExpect(jsonPath("$.items[0].endpointName").value("rx"))
                .andExpect(jsonPath("$.items[0].eventType").value("order.created"));
        mvc.perform(get("/api/v1/deliveries?status=DELIVERED&status=DEAD_LETTERED&endpointId=" + endpointId)
                        .header("Authorization", account.bearer()))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(failed.toString()))
                .andExpect(jsonPath("$.items[1].id").value(ok.toString()));
        mvc.perform(get("/api/v1/deliveries?status=NOPE").header("Authorization", account.bearer()))
                .andExpect(status().isBadRequest());

        Account other = data.newAccount();
        mvc.perform(get("/api/v1/deliveries").header("Authorization", other.bearer()))
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/deliveries/" + ok).header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());
    }

    /** Verifies the Standard Webhooks signature without using any platform code. */
    static boolean verifySignature(String secret, TestReceiver.Request request) throws Exception {
        byte[] key = Base64.getDecoder().decode(secret.substring("whsec_".length()));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update((request.header("webhook-id") + "." + request.header("webhook-timestamp") + ".")
                .getBytes(StandardCharsets.UTF_8));
        byte[] expected = mac.doFinal(request.body());
        for (String part : request.header("webhook-signature").split(" ")) {
            if (part.startsWith("v1,") && MessageDigest.isEqual(expected, Base64.getDecoder().decode(part.substring(3)))) {
                return true;
            }
        }
        return false;
    }
}
