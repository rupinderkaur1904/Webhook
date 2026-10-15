package dev.webhooks.platform.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import dev.webhooks.platform.support.TestReceiver;
import dev.webhooks.platform.support.TestReceiver.Response;
import dev.webhooks.platform.worker.DeliveryProcessor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Retry scheduling, exhaustion and permanent failures against real PostgreSQL and a real receiver.
 * Test profile: max-attempts 3, base-delay 10s, max-delay 60s, max-retry-after 300s. Waiting for a
 * scheduled retry is simulated by moving next_attempt_at to now; everything else runs as in production.
 */
@IntegrationTest
class RetryFlowTest {

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired DeliveryRepository deliveries;
    @Autowired DeliveryProcessor processor;

    TestReceiver receiver;
    Account account;
    String endpointId;

    @BeforeEach
    void setUp() throws Exception {
        data.clearDeliveries();
        receiver = new TestReceiver();
        account = data.newAccount();
        endpointId = json.readTree(mvc.perform(post("/api/v1/endpoints").with(account.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"retry\",\"url\":\"" + receiver.url("/hook") + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("endpoint").get("id").asString();
    }

    @AfterEach
    void tearDown() {
        receiver.close();
    }

    UUID submit(String key) throws Exception {
        String body = mvc.perform(post("/api/v1/events").header("Authorization", account.bearer())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"order.created\",\"payload\":{\"k\":\"" + key + "\"}}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("deliveries").get(0).get("id").asString());
    }

    /** Runs the next attempt if one is due; returns whether anything was claimed. */
    boolean attempt() {
        List<ClaimedDelivery> claimed = deliveries.claimDue(10, "test-worker", Duration.ofSeconds(60));
        claimed.forEach(processor::process);
        return !claimed.isEmpty();
    }

    void makeDue(UUID deliveryId) {
        jdbc.sql("UPDATE deliveries SET next_attempt_at = now() WHERE id = :id AND status = 'RETRYING'")
                .param("id", deliveryId).update();
    }

    JsonNode detail(UUID deliveryId) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/deliveries/" + deliveryId).header("Authorization", account.bearer()))
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void transientFailuresAreRetriedUntilTheReceiverRecovers() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        receiver.respondWith(r -> Response.status(calls.incrementAndGet() <= 2 ? 500 : 200));
        UUID id = submit("recover");

        assertThat(attempt()).isTrue();
        Instant afterFirst = Instant.now();
        JsonNode first = detail(id);
        assertThat(first.get("delivery").get("status").asString()).isEqualTo("RETRYING");
        // First retry: raw backoff 10s with equal jitter -> between 5s and 10s from now.
        Instant next = Instant.parse(first.get("delivery").get("nextAttemptAt").asString());
        assertThat(next).isBetween(afterFirst.plusSeconds(4), afterFirst.plusSeconds(11));
        assertThat(attempt()).as("retry is not claimed before it is due").isFalse();

        makeDue(id);
        assertThat(attempt()).isTrue();
        makeDue(id);
        assertThat(attempt()).isTrue();

        JsonNode done = detail(id);
        assertThat(done.get("delivery").get("status").asString()).isEqualTo("DELIVERED");
        assertThat(done.get("delivery").get("attemptCount").asInt()).isEqualTo(3);
        assertThat(done.get("delivery").get("nextAttemptAt").isNull()).isTrue();
        assertThat(done.get("attempts")).extracting(a -> a.get("responseStatus").asInt()).containsExactly(500, 500, 200);
        assertThat(done.get("attempts")).extracting(a -> a.get("status").asString())
                .containsExactly("FAILED", "FAILED", "SUCCEEDED");
        // Every attempt carries the same webhook-id and body; only the attempt counter changes.
        assertThat(receiver.requests()).extracting(r -> r.header("webhook-id")).containsOnly(
                done.get("delivery").get("eventId").asString());
        assertThat(receiver.requests()).extracting(r -> r.header("X-Webhook-Attempt")).containsExactly("1", "2", "3");
        assertThat(receiver.requests()).extracting(TestReceiver.Request::bodyText).containsOnly(receiver.requests().getFirst().bodyText());
    }

    @Test
    void exhaustedRetriesAreDeadLetteredWithFullHistory() throws Exception {
        receiver.respondWith(r -> Response.status(502));
        UUID id = submit("exhaust");
        attempt();
        makeDue(id);
        attempt();
        makeDue(id);
        attempt();

        JsonNode dl = detail(id);
        assertThat(dl.get("delivery").get("status").asString()).isEqualTo("DEAD_LETTERED");
        assertThat(dl.get("delivery").get("deadLetterReason").asString()).isEqualTo("RETRIES_EXHAUSTED");
        assertThat(dl.get("delivery").get("attemptCount").asInt()).isEqualTo(3);
        assertThat(dl.get("delivery").get("deadLetteredAt").isNull()).isFalse();
        assertThat(dl.get("delivery").get("lastError").asString()).isEqualTo("HTTP 502");
        assertThat(dl.get("attempts")).hasSize(3).allSatisfy(a -> {
            assertThat(a.get("outcome").asString()).isEqualTo("HTTP_SERVER_ERROR");
            assertThat(a.get("retryable").asBoolean()).isTrue();
        });
        makeDue(id);
        assertThat(attempt()).as("a dead-lettered delivery is never attempted again").isFalse();
        assertThat(receiver.requests()).hasSize(3);
    }

    @Test
    void permanentClientErrorsAreDeadLetteredWithoutRetrying() throws Exception {
        receiver.respondWith(r -> Response.status(404));
        UUID id = submit("permanent");
        attempt();

        JsonNode dl = detail(id);
        assertThat(dl.get("delivery").get("status").asString()).isEqualTo("DEAD_LETTERED");
        assertThat(dl.get("delivery").get("deadLetterReason").asString()).isEqualTo("NON_RETRYABLE_RESPONSE");
        assertThat(dl.get("attempts").get(0).get("retryable").asBoolean()).isFalse();
        assertThat(dl.get("delivery").get("attemptCount").asInt()).isEqualTo(1);
    }

    @Test
    void requestTimeout408IsRetried() throws Exception {
        receiver.respondWith(r -> Response.status(408));
        UUID id = submit("408");
        attempt();
        assertThat(detail(id).get("delivery").get("status").asString()).isEqualTo("RETRYING");
    }

    @Test
    void rateLimitHonoursRetryAfterAndCapsIt() throws Exception {
        receiver.respondWith(r -> new Response(429, Map.of("Retry-After", "120"), "slow down", 0));
        UUID honoured = submit("429-a");
        Instant before = Instant.now();
        attempt();
        JsonNode a = detail(honoured);
        assertThat(a.get("attempts").get(0).get("outcome").asString()).isEqualTo("RATE_LIMITED");
        assertThat(a.get("attempts").get(0).get("retryAfterSeconds").asInt()).isEqualTo(120);
        assertThat(Instant.parse(a.get("delivery").get("nextAttemptAt").asString()))
                .isBetween(before.plusSeconds(119), Instant.now().plusSeconds(121));

        receiver.respondWith(r -> new Response(429, Map.of("Retry-After", "86400"), "", 0));
        UUID capped = submit("429-b");
        before = Instant.now();
        attempt();
        Instant next = Instant.parse(detail(capped).get("delivery").get("nextAttemptAt").asString());
        assertThat(next).isBetween(before.plusSeconds(299), Instant.now().plusSeconds(301)); // max-retry-after 300s
    }

    @Test
    void retryAfterIsIgnoredWhereItHasNoSchedulingMeaning() throws Exception {
        receiver.respondWith(r -> new Response(500, Map.of("Retry-After", "250"), "", 0));
        UUID id = submit("500-retry-after");
        Instant before = Instant.now();
        attempt();
        JsonNode d = detail(id);
        assertThat(Instant.parse(d.get("delivery").get("nextAttemptAt").asString())).isBefore(before.plusSeconds(11));
        assertThat(d.get("attempts").get(0).get("retryAfterSeconds").isNull()).isTrue();
    }

    @Test
    void endpointDeletedDuringAnAttemptCancelsInsteadOfRetrying() throws Exception {
        receiver.respondWith(r -> Response.status(500));
        UUID id = submit("deleted");
        List<ClaimedDelivery> claimed = deliveries.claimDue(10, "w", Duration.ofSeconds(60));
        mvc.perform(delete("/api/v1/endpoints/" + endpointId).with(account.session()))
                .andExpect(status().isNoContent());
        processor.process(claimed.getFirst());

        JsonNode d = detail(id);
        assertThat(d.get("delivery").get("status").asString()).isEqualTo("CANCELLED");
        assertThat(d.get("attempts").get(0).get("status").asString()).isEqualTo("FAILED");
    }
}
