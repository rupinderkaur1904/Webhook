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
import dev.webhooks.platform.worker.LeaseReaper;
import java.time.Duration;
import java.time.Instant;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Crash recovery through leases (docs/PLAN.md §7.2). A "crashed" worker is one that claimed a
 * delivery and never reported; its lease is then expired by moving {@code lease_expires_at} into
 * the past, which is exactly the database state a real crash leaves behind once the lease runs out.
 * Test profile: 3 attempts, 10s base delay.
 */
@IntegrationTest
class LeaseRecoveryTest {

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired DeliveryRepository deliveries;
    @Autowired DeliveryProcessor processor;
    @Autowired LeaseReaper reaper;

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
                        .content("{\"name\":\"recovery\",\"url\":\"" + receiver.url("/hook") + "\"}"))
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

    /** A worker claims due work and then dies without reporting. */
    List<ClaimedDelivery> claimAndCrash(String worker) {
        return deliveries.claimDue(500, worker, Duration.ofSeconds(60));
    }

    void expireLeases() {
        jdbc.sql("UPDATE deliveries SET lease_expires_at = now() - interval '1 second' WHERE status = 'IN_FLIGHT'").update();
    }

    void makeDue(UUID id) {
        jdbc.sql("UPDATE deliveries SET next_attempt_at = now() WHERE id = :id AND status = 'RETRYING'").param("id", id).update();
    }

    void attempt() {
        deliveries.claimDue(10, "healthy-worker", Duration.ofSeconds(60)).forEach(processor::process);
    }

    JsonNode detail(UUID id) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/deliveries/" + id).header("Authorization", account.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void crashedAttemptIsAbandonedAndTheDeliveryIsRetried() throws Exception {
        UUID id = submit("crash");
        claimAndCrash("crashed-worker");
        JsonNode inFlight = detail(id);
        assertThat(inFlight.get("delivery").get("status").asString()).isEqualTo("IN_FLIGHT");
        assertThat(inFlight.get("lease").get("owner").asString()).isEqualTo("crashed-worker");
        assertThat(inFlight.get("attempts").get(0).get("status").asString()).isEqualTo("STARTED");

        expireLeases();
        Instant reapedAt = Instant.now();
        assertThat(reaper.reapExpired()).isEqualTo(1);

        JsonNode recovered = detail(id);
        assertThat(recovered.get("delivery").get("status").asString()).isEqualTo("RETRYING");
        assertThat(recovered.get("lease").isNull()).isTrue();
        assertThat(recovered.get("delivery").get("lastError").asString()).contains("Lease expired");
        // Normal backoff for attempt 1: 10s raw delay with equal jitter -> 5-10s.
        assertThat(Instant.parse(recovered.get("delivery").get("nextAttemptAt").asString()))
                .isBetween(reapedAt.plusSeconds(4), reapedAt.plusSeconds(11));
        JsonNode abandoned = recovered.get("attempts").get(0);
        assertThat(abandoned.get("status").asString()).isEqualTo("ABANDONED");
        assertThat(abandoned.get("outcome").asString()).isEqualTo("LEASE_EXPIRED");
        assertThat(abandoned.get("retryable").asBoolean()).isTrue();
        assertThat(abandoned.get("errorMessage").asString()).contains("crashed-worker");
        assertThat(abandoned.get("finishedAt").isNull()).isFalse();

        makeDue(id);
        attempt();
        JsonNode done = detail(id);
        assertThat(done.get("delivery").get("status").asString()).isEqualTo("DELIVERED");
        assertThat(done.get("attempts")).extracting(a -> a.get("status").asString()).containsExactly("ABANDONED", "SUCCEEDED");
        assertThat(receiver.requests()).hasSize(1);
    }

    @Test
    void liveLeasesAreLeftAlone() throws Exception {
        UUID id = submit("live");
        claimAndCrash("busy-worker");
        assertThat(reaper.reapExpired()).isZero();
        assertThat(detail(id).get("delivery").get("status").asString()).isEqualTo("IN_FLIGHT");
    }

    @Test
    void abandonedAttemptsCountSoAPoisonDeliveryCannotLoopForever() throws Exception {
        UUID id = submit("poison");
        for (int i = 0; i < 3; i++) {
            makeDue(id);
            assertThat(claimAndCrash("worker-" + i)).hasSize(1);
            expireLeases();
            assertThat(reaper.reapExpired()).isEqualTo(1);
        }
        JsonNode dead = detail(id);
        assertThat(dead.get("delivery").get("status").asString()).isEqualTo("DEAD_LETTERED");
        assertThat(dead.get("delivery").get("deadLetterReason").asString()).isEqualTo("RETRIES_EXHAUSTED");
        assertThat(dead.get("delivery").get("attemptCount").asInt()).isEqualTo(3);
        assertThat(dead.get("attempts")).extracting(a -> a.get("outcome").asString())
                .containsExactly("LEASE_EXPIRED", "LEASE_EXPIRED", "LEASE_EXPIRED");
        assertThat(dead.get("replayEligibility").get("eligible").asBoolean()).isTrue();
    }

    @Test
    void deliveryWhoseEndpointWasDeletedDuringTheCrashIsCancelled() throws Exception {
        UUID id = submit("deleted");
        claimAndCrash("crashed-worker");
        mvc.perform(delete("/api/v1/endpoints/" + endpointId).with(account.session()))
                .andExpect(status().isNoContent());
        expireLeases();
        reaper.reapExpired();
        assertThat(detail(id).get("delivery").get("status").asString()).isEqualTo("CANCELLED");
    }

    @Test
    void staleWorkerThatReportsAfterRecoveryIsFencedOutButItsObservationIsKept() throws Exception {
        UUID id = submit("stale");
        ClaimedDelivery stale = claimAndCrash("slow-worker").getFirst();
        expireLeases();
        reaper.reapExpired();
        makeDue(id);
        attempt(); // a healthy worker delivers attempt 2
        assertThat(detail(id).get("delivery").get("status").asString()).isEqualTo("DELIVERED");

        // The slow worker wakes up, sends, and tries to record its (successful) result.
        processor.process(stale);

        JsonNode after = detail(id);
        assertThat(after.get("delivery").get("status").asString()).isEqualTo("DELIVERED");
        assertThat(after.get("delivery").get("attemptCount").asInt()).isEqualTo(2);
        JsonNode first = after.get("attempts").get(0);
        assertThat(first.get("status").asString()).as("not counted as an applied result").isEqualTo("ABANDONED");
        assertThat(first.get("reportedAfterLeaseExpiry").asBoolean()).isTrue();
        assertThat(first.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(first.get("responseStatus").asInt()).isEqualTo(200);
        assertThat(after.get("attempts").get(1).get("reportedAfterLeaseExpiry").asBoolean()).isFalse();
        // At-least-once: the receiver really got the event twice, with the same webhook-id.
        assertThat(receiver.requests()).hasSize(2);
        assertThat(receiver.requests()).extracting(r -> r.header("webhook-id")).containsOnly(
                after.get("delivery").get("eventId").asString());
    }

    @Test
    void staleFailureCannotOverwriteAScheduledRetry() throws Exception {
        receiver.respondWith(r -> Response.status(400)); // would dead-letter if it were applied
        UUID id = submit("stale-failure");
        ClaimedDelivery stale = claimAndCrash("slow-worker").getFirst();
        expireLeases();
        reaper.reapExpired();
        String nextBefore = detail(id).get("delivery").get("nextAttemptAt").asString();

        processor.process(stale);

        JsonNode after = detail(id);
        assertThat(after.get("delivery").get("status").asString()).isEqualTo("RETRYING");
        assertThat(after.get("delivery").get("nextAttemptAt").asString()).isEqualTo(nextBefore);
        assertThat(after.get("attempts").get(0).get("responseStatus").asInt()).isEqualTo(400);
        assertThat(after.get("attempts").get(0).get("status").asString()).isEqualTo("ABANDONED");
    }

    @Test
    void concurrentReapersRecoverEachDeliveryExactlyOnce() throws Exception {
        int count = 250;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(submit("bulk-" + i));
        }
        assertThat(claimAndCrash("crashed-worker")).hasSize(count);
        expireLeases();

        int reapers = 4;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < reapers; i++) {
            tasks.add(() -> {
                start.await();
                return reaper.reapExpired();
            });
        }
        int total = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(reapers)) {
            List<Future<Integer>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<Integer> f : futures) {
                total += f.get();
            }
        }
        assertThat(total).isEqualTo(count);
        assertThat(jdbc.sql("SELECT count(*) FROM delivery_attempts WHERE status = 'ABANDONED' AND delivery_id = ANY(:ids)")
                .param("ids", ids.toArray(UUID[]::new)).query(Long.class).single()).isEqualTo(count);
        assertThat(jdbc.sql("SELECT count(*) FROM deliveries WHERE status = 'RETRYING' AND attempt_count = 1 AND id = ANY(:ids)")
                .param("ids", ids.toArray(UUID[]::new)).query(Long.class).single()).isEqualTo(count);
    }

    @Test
    void crashedReplayIsRecoveredWithinItsOwnChain() throws Exception {
        receiver.respondWith(r -> Response.status(400));
        UUID original = submit("replay-crash");
        attempt();
        receiver.respondWith(r -> Response.status(200));
        UUID replay = UUID.fromString(json.readTree(mvc.perform(post("/api/v1/deliveries/" + original + "/replay")
                        .with(account.session())).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString()).get("id").asString());

        claimAndCrash("crashed-worker");
        expireLeases();
        reaper.reapExpired();
        // Still in progress (RETRYING), so the chain still refuses a second replay.
        mvc.perform(post("/api/v1/deliveries/" + original + "/replay").with(account.session()))
                .andExpect(status().isConflict());
        makeDue(replay);
        attempt();

        JsonNode chain = detail(original).get("chain");
        assertThat(chain).extracting(d -> d.get("status").asString()).containsExactly("DEAD_LETTERED", "DELIVERED");
        assertThat(detail(replay).get("attempts")).extracting(a -> a.get("status").asString())
                .containsExactly("ABANDONED", "SUCCEEDED");
        assertThat(detail(original).get("attempts")).hasSize(1);
    }
}
