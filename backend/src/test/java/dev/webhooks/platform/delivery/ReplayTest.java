package dev.webhooks.platform.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.webhooks.platform.common.ApiException;
import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import dev.webhooks.platform.support.TestReceiver;
import dev.webhooks.platform.support.TestReceiver.Response;
import dev.webhooks.platform.worker.DeliveryProcessor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Manual replay of dead letters (docs/PLAN.md §7.7). Test profile: max 2 replays per chain, 3 attempts. */
@IntegrationTest
class ReplayTest {

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired DeliveryRepository deliveries;
    @Autowired DeliveryProcessor processor;
    @Autowired ReplayService replayService;

    TestReceiver receiver;
    Account account;
    String endpointId;

    @BeforeEach
    void setUp() throws Exception {
        data.clearDeliveries();
        receiver = new TestReceiver();
        account = data.newAccount();
        endpointId = json.readTree(mvc.perform(post("/api/v1/endpoints").header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"replay\",\"url\":\"" + receiver.url("/hook") + "\"}"))
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

    void attempt() {
        deliveries.claimDue(10, "test-worker", Duration.ofSeconds(60)).forEach(processor::process);
    }

    /** A delivery dead-lettered by a permanent 400 after one attempt. */
    UUID deadLetter(String key) throws Exception {
        receiver.respondWith(r -> Response.status(400));
        UUID id = submit(key);
        attempt();
        assertThat(detail(id).get("delivery").get("status").asString()).isEqualTo("DEAD_LETTERED");
        return id;
    }

    JsonNode detail(UUID deliveryId) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/deliveries/" + deliveryId).header("Authorization", account.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    ResultActions replay(UUID deliveryId, Account who) throws Exception {
        return mvc.perform(post("/api/v1/deliveries/" + deliveryId + "/replay").header("Authorization", who.bearer()));
    }

    UUID replayOk(UUID deliveryId) throws Exception {
        String body = replay(deliveryId, account).andExpect(status().isAccepted()).andReturn().getResponse()
                .getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    @Test
    void replayCreatesLinkedDeliveryAndLeavesTheDeadLetterUntouched() throws Exception {
        UUID original = deadLetter("replay-ok");
        JsonNode before = detail(original);
        assertThat(before.get("replayEligibility").get("eligible").asBoolean()).isTrue();

        receiver.respondWith(r -> Response.status(200));
        String body = replay(original, account)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attemptCount").value(0))
                .andExpect(jsonPath("$.maxAttempts").value(3))
                .andExpect(jsonPath("$.replayOfDeliveryId").value(original.toString()))
                .andExpect(jsonPath("$.rootDeliveryId").value(original.toString()))
                .andReturn().getResponse().getContentAsString();
        UUID replayId = UUID.fromString(json.readTree(body).get("id").asString());
        attempt();

        JsonNode replayed = detail(replayId);
        assertThat(replayed.get("delivery").get("status").asString()).isEqualTo("DELIVERED");
        assertThat(replayed.get("delivery").get("eventId").asString())
                .isEqualTo(before.get("delivery").get("eventId").asString());
        assertThat(replayed.get("chain")).extracting(d -> d.get("id").asString())
                .containsExactly(original.toString(), replayId.toString());

        JsonNode after = detail(original);
        assertThat(after.get("delivery").get("status").asString()).isEqualTo("DEAD_LETTERED");
        assertThat(after.get("delivery").get("deadLetterReason").asString()).isEqualTo("NON_RETRYABLE_RESPONSE");
        assertThat(after.get("attempts")).hasSize(1);
        assertThat(after.get("attempts").get(0).get("responseStatus").asInt()).isEqualTo(400);
        assertThat(after.get("replayEligibility").get("replayCount").asInt()).isEqualTo(1);

        // Same event, so the same webhook-id; a different delivery id; fresh attempt numbering.
        assertThat(receiver.requests()).hasSize(2);
        assertThat(receiver.requests()).extracting(r -> r.header("webhook-id")).containsOnly(
                after.get("delivery").get("eventId").asString());
        assertThat(receiver.requests()).extracting(r -> r.header("X-Webhook-Delivery-Id"))
                .containsExactly(original.toString(), replayId.toString());
        assertThat(receiver.requests()).extracting(r -> r.header("X-Webhook-Attempt")).containsExactly("1", "1");
        assertThat(jdbc.sql("SELECT replayed_by_user_id FROM deliveries WHERE id = :id").param("id", replayId)
                .query(UUID.class).single()).isEqualTo(account.id());
    }

    @Test
    void onlyDeadLettersCanBeReplayed() throws Exception {
        UUID pending = submit("pending");
        replay(pending, account).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPLAY_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.reason").value("NOT_DEAD_LETTERED"));
        assertThat(detail(pending).get("replayEligibility").get("reason").asString()).isEqualTo("NOT_DEAD_LETTERED");

        receiver.respondWith(r -> Response.status(200));
        attempt();
        replay(pending, account).andExpect(jsonPath("$.reason").value("NOT_DEAD_LETTERED"));
    }

    @Test
    void secondReplayWhileOneIsInProgressIsRejected() throws Exception {
        UUID original = deadLetter("in-progress");
        replayOk(original);
        replay(original, account).andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("REPLAY_IN_PROGRESS"));
        assertThat(detail(original).get("replayEligibility").get("reason").asString()).isEqualTo("REPLAY_IN_PROGRESS");
    }

    @Test
    void concurrentReplaysOfTheSameDeadLetterCreateExactlyOneDelivery() throws Exception {
        UUID original = deadLetter("concurrent");
        int threads = 12;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                start.await();
                try {
                    replayService.replay(account.id(), original);
                    return "OK";
                } catch (ApiException e) {
                    return String.valueOf(e.properties().get("reason"));
                }
            });
        }
        List<String> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<String>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<String> f : futures) {
                results.add(f.get());
            }
        }
        assertThat(results).filteredOn("OK"::equals).hasSize(1);
        assertThat(results).filteredOn(r -> !r.equals("OK")).containsOnly("REPLAY_IN_PROGRESS");
        assertThat(jdbc.sql("SELECT count(*) FROM deliveries WHERE root_delivery_id = :root")
                .param("root", original).query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void replayLimitCountsEveryReplayInTheChain() throws Exception {
        UUID original = deadLetter("limit");
        UUID first = replayOk(original);
        attempt(); // receiver still answers 400: the replay dead-letters too
        UUID second = replayOk(first); // replaying the replay is allowed and belongs to the same chain
        attempt();

        assertThat(detail(second).get("delivery").get("rootDeliveryId").asString()).isEqualTo(original.toString());
        replay(original, account).andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("REPLAY_LIMIT_REACHED"));
        replay(second, account).andExpect(jsonPath("$.reason").value("REPLAY_LIMIT_REACHED"));
        JsonNode eligibility = detail(second).get("replayEligibility");
        assertThat(eligibility.get("replayCount").asInt()).isEqualTo(2);
        assertThat(eligibility.get("maxReplays").asInt()).isEqualTo(2);
        assertThat(detail(original).get("chain")).hasSize(3);
    }

    @Test
    void disabledOrDeletedEndpointCannotBeReplayedTo() throws Exception {
        UUID original = deadLetter("endpoint-state");
        mvc.perform(patch("/api/v1/endpoints/" + endpointId).header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        replay(original, account).andExpect(jsonPath("$.reason").value("ENDPOINT_DISABLED"));

        mvc.perform(delete("/api/v1/endpoints/" + endpointId).header("Authorization", account.bearer()))
                .andExpect(status().isNoContent());
        replay(original, account).andExpect(jsonPath("$.reason").value("ENDPOINT_DELETED"));
        assertThat(detail(original).get("replayEligibility").get("eligible").asBoolean()).isFalse();
    }

    @Test
    void anotherAccountCannotSeeOrReplayTheDelivery() throws Exception {
        UUID original = deadLetter("isolation");
        Account other = data.newAccount();
        replay(original, other).andExpect(status().isNotFound());
        replay(UUID.randomUUID(), account).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT count(*) FROM deliveries WHERE root_delivery_id = :root")
                .param("root", original).query(Long.class).single()).isEqualTo(1L);
    }
}
