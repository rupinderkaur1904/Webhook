package dev.webhooks.platform.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.webhooks.platform.delivery.DeliveryRepository.ExpiredLease;
import dev.webhooks.platform.endpoint.EndpointService;
import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import dev.webhooks.platform.support.TestReceiver;
import dev.webhooks.platform.support.TestReceiver.Response;
import dev.webhooks.platform.worker.DeliveryProcessor;
import dev.webhooks.platform.worker.LeaseReaper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Audit: worker state machine under concurrency and malformed data. Each race is forced
 * deterministically: one transaction is held open at a chosen point with latches, and the other
 * side is released only once PostgreSQL reports it waiting on a lock (or it has finished).
 */
@IntegrationTest
class AuditWorkerFaultsTest {

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired DeliveryRepository deliveries;
    @Autowired DeliveryProcessor processor;
    @Autowired LeaseReaper reaper;
    @Autowired EndpointService endpointService;
    @Autowired TransactionTemplate tx;

    TestReceiver receiver;
    Account account;
    UUID endpointId;
    String secret;
    ExecutorService pool;

    @BeforeEach
    void setUp() throws Exception {
        data.clearDeliveries();
        receiver = new TestReceiver();
        account = data.newAccount();
        JsonNode created = json.readTree(mvc.perform(post("/api/v1/endpoints").with(account.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"audit\",\"url\":\"" + receiver.url("/hook") + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        endpointId = UUID.fromString(created.get("endpoint").get("id").asString());
        secret = created.get("secret").asString();
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        receiver.close();
    }

    UUID submit(String key) throws Exception {
        String body = mvc.perform(post("/api/v1/events").header("Authorization", account.bearer())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"order.created\",\"payload\":{\"k\":\"" + key + "\"}}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("deliveries").get(0).get("id").asString());
    }

    String statusOf(UUID id) {
        return jdbc.sql("SELECT status FROM deliveries WHERE id = :id").param("id", id).query(String.class).single();
    }

    int lockWaiters() {
        return jdbc.sql("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock'
                        """).query(Integer.class).single();
    }

    static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition within 15s").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A worker reports its result at the moment the reaper recovers its expired lease. The reaper
     * locks the delivery row first; the worker must neither deadlock with it nor lose its
     * observation: expected outcome is reaper wins (RETRYING), the attempt stays ABANDONED and keeps
     * the late observation flagged reported_after_lease_expiry (PLAN Stage 4 "Late results").
     */
    @Test
    void lateWorkerResultRacingTheReaperNeitherDeadlocksNorLosesTheObservation() throws Exception {
        UUID id = submit("reaper-race");
        ClaimedDelivery claim = deliveries.claimDue(10, "slow-worker", Duration.ofSeconds(60)).getFirst();
        jdbc.sql("UPDATE deliveries SET lease_expires_at = now() - interval '1 second' WHERE id = :id").param("id", id).update();

        CountDownLatch reaperHoldsLock = new CountDownLatch(1);
        CountDownLatch reaperProceed = new CountDownLatch(1);
        // Same repository calls, in the same order and transaction, as LeaseReaper.reapBatch().
        Future<Integer> reaperRun = pool.submit(() -> tx.execute(s -> {
            List<ExpiredLease> leases = deliveries.lockExpiredLeases(10);
            reaperHoldsLock.countDown();
            awaitLatch(reaperProceed);
            for (ExpiredLease lease : leases) {
                deliveries.abandonAttempt(lease, "Worker slow-worker did not report a result before its lease expired");
                deliveries.releaseExpiredLease(lease, Duration.ofSeconds(10), "Lease expired");
            }
            return leases.size();
        }));
        awaitLatch(reaperHoldsLock);
        Future<?> worker = pool.submit(() -> processor.process(claim));
        await(() -> worker.isDone() || lockWaiters() > 0);
        reaperProceed.countDown();

        assertThat(reaperRun.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        worker.get(30, TimeUnit.SECONDS); // a deadlock abort surfaces here (or in the reaper) as an exception

        assertThat(statusOf(id)).isEqualTo("RETRYING");
        Map<String, Object> attempt = jdbc.sql("""
                        SELECT status, outcome, response_status, reported_after_lease_expiry
                        FROM delivery_attempts WHERE delivery_id = :id
                        """).param("id", id).query().singleRow();
        assertThat(attempt.get("status")).isEqualTo("ABANDONED");
        assertThat(attempt.get("reported_after_lease_expiry")).isEqualTo(true);
        assertThat(attempt.get("outcome")).isEqualTo("SUCCESS");
        assertThat(attempt.get("response_status")).isEqualTo(200);
    }

    /**
     * The endpoint is deleted while a failed attempt's retry is being recorded (after the recording
     * statement read the endpoint, before it committed). Expected: the delivery ends CANCELLED like
     * every other queued delivery of a deleted endpoint; it must not stay RETRYING forever (the claim
     * query never picks up deliveries of deleted endpoints).
     */
    @Test
    void retryRecordedWhileTheEndpointIsBeingDeletedDoesNotStayQueuedForever() throws Exception {
        UUID id = submit("delete-race");
        ClaimedDelivery claim = deliveries.claimDue(10, "w", Duration.ofSeconds(60)).getFirst();

        CountDownLatch marked = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        Future<Boolean> recording = pool.submit(() -> tx.execute(s -> {
            boolean updated = deliveries.markRetrying(claim, Duration.ofSeconds(10), 500, "HTTP 500");
            marked.countDown();
            awaitLatch(proceed);
            return updated;
        }));
        awaitLatch(marked);
        Future<?> deletion = pool.submit(() -> endpointService.delete(account.id(), endpointId));
        await(() -> deletion.isDone() || lockWaiters() > 0);
        proceed.countDown();
        assertThat(recording.get(30, TimeUnit.SECONDS)).isTrue();
        deletion.get(30, TimeUnit.SECONDS);

        // The regular maintenance pass runs.
        reaper.reapExpired();

        assertThat(statusOf(id)).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT next_attempt_at IS NULL FROM deliveries WHERE id = :id").param("id", id)
                .query(Boolean.class).single()).isTrue();
    }

    /**
     * Poison data: the endpoint's stored secret cannot be decrypted (corrupted row or wrong key).
     * Expected: the worker survives, other deliveries in the same batch are unaffected, and the
     * poisoned delivery is bounded by its attempt budget through lease expiry.
     */
    @Test
    void undecryptableSecretCannotBlockTheQueueAndIsBoundedByTheBudget() throws Exception {
        UUID poisoned = submit("poison-secret");
        jdbc.sql("UPDATE endpoints SET secret_ciphertext = '\\x01deadbeef00000000000000000000000000000000000000' WHERE id = :id")
                .param("id", endpointId).update();
        // A healthy endpoint with its own delivery, queued behind the poisoned one.
        String healthyId = json.readTree(mvc.perform(post("/api/v1/endpoints").with(account.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"healthy\",\"url\":\"" + receiver.url("/ok") + "\"}"))
                .andReturn().getResponse().getContentAsString()).get("endpoint").get("id").asString();
        String body = mvc.perform(post("/api/v1/events").header("Authorization", account.bearer())
                        .header("Idempotency-Key", "healthy").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"order.created\",\"payload\":{},\"endpointIds\":[\"" + healthyId + "\"]}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID healthy = UUID.fromString(json.readTree(body).get("deliveries").get(0).get("id").asString());

        for (int attempt = 1; attempt <= 3; attempt++) {
            jdbc.sql("UPDATE deliveries SET next_attempt_at = now() WHERE id = :id AND status = 'RETRYING'")
                    .param("id", poisoned).update();
            for (ClaimedDelivery c : deliveries.claimDue(10, "w", Duration.ofSeconds(60))) {
                try {
                    processor.process(c); // what DeliveryWorker does: an exception is logged, the loop continues
                } catch (RuntimeException expected) {
                    assertThat(c.deliveryId()).isEqualTo(poisoned);
                }
            }
            jdbc.sql("UPDATE deliveries SET lease_expires_at = now() - interval '1 second' WHERE status = 'IN_FLIGHT'").update();
            reaper.reapExpired();
        }
        assertThat(statusOf(healthy)).isEqualTo("DELIVERED");
        assertThat(statusOf(poisoned)).isEqualTo("DEAD_LETTERED");
        assertThat(jdbc.sql("SELECT dead_letter_reason FROM deliveries WHERE id = :id").param("id", poisoned)
                .query(String.class).single()).isEqualTo("RETRIES_EXHAUSTED");
        assertThat(jdbc.sql("SELECT count(*) FROM delivery_attempts WHERE delivery_id = :id AND outcome = 'LEASE_EXPIRED'")
                .param("id", poisoned).query(Integer.class).single()).isEqualTo(3);
        assertThat(receiver.requests()).extracting(TestReceiver.Request::path).containsExactly("/ok");
    }

    /**
     * Secret rotation (no grace period) and a URL edit land between claim and send. Expected: the
     * attempt goes to the URL recorded on its attempt row (captured at claim) and is signed with one
     * consistent secret set read in a single row read: here only the new secret.
     */
    @Test
    void rotationAndUrlEditDuringAnAttemptGiveOneConsistentSignatureAndDestination() throws Exception {
        UUID id = submit("rotate");
        ClaimedDelivery claim = deliveries.claimDue(10, "w", Duration.ofSeconds(60)).getFirst();
        String rotated = json.readTree(mvc.perform(post("/api/v1/endpoints/" + endpointId + "/rotate-secret")
                        .with(account.session()).contentType(MediaType.APPLICATION_JSON).content("{\"gracePeriodHours\":0}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("secret").asString();
        mvc.perform(patch("/api/v1/endpoints/" + endpointId).with(account.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + receiver.url("/moved") + "\"}"))
                .andExpect(status().isOk());

        processor.process(claim);

        assertThat(statusOf(id)).isEqualTo("DELIVERED");
        TestReceiver.Request request = receiver.requests().getFirst();
        assertThat(request.path()).isEqualTo("/hook");
        assertThat(jdbc.sql("SELECT request_url FROM delivery_attempts WHERE delivery_id = :id").param("id", id)
                .query(String.class).single()).isEqualTo(receiver.url("/hook"));
        assertThat(request.header("webhook-signature").split(" ")).hasSize(1);
        assertThat(signedWith(rotated, request)).isTrue();
        assertThat(signedWith(secret, request)).isFalse();

        // With a grace period both secrets sign the same request, each signature valid on its own.
        UUID second = submit("rotate-grace");
        ClaimedDelivery claim2 = deliveries.claimDue(10, "w", Duration.ofSeconds(60)).getFirst();
        String rotatedAgain = json.readTree(mvc.perform(post("/api/v1/endpoints/" + endpointId + "/rotate-secret")
                        .with(account.session()).contentType(MediaType.APPLICATION_JSON).content("{\"gracePeriodHours\":1}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("secret").asString();
        processor.process(claim2);
        assertThat(statusOf(second)).isEqualTo("DELIVERED");
        TestReceiver.Request graced = receiver.requests().get(1);
        assertThat(graced.path()).isEqualTo("/moved");
        assertThat(graced.header("webhook-signature").split(" ")).hasSize(2);
        assertThat(signedWith(rotatedAgain, graced)).isTrue();
        assertThat(signedWith(rotated, graced)).isTrue();
    }

    /**
     * The circuit opens (other deliveries failed) while this delivery is in flight. Expected: its
     * success is recorded (DELIVERED), and as a non-probe result it does not close the circuit.
     */
    @Test
    void circuitOpeningDuringAnAttemptDoesNotLoseTheResult() throws Exception {
        UUID inFlight = submit("circuit-a");
        ClaimedDelivery claim = deliveries.claimDue(10, "w", Duration.ofSeconds(60)).getFirst();
        UUID queued = submit("circuit-b");
        jdbc.sql("""
                        UPDATE endpoints SET circuit_state = 'OPEN', consecutive_failures = 10, circuit_open_count = 1,
                               circuit_opened_at = now(), circuit_retry_at = now() + interval '1 hour'
                        WHERE id = :id
                        """).param("id", endpointId).update();

        processor.process(claim);

        assertThat(statusOf(inFlight)).isEqualTo("DELIVERED");
        assertThat(jdbc.sql("SELECT circuit_state FROM endpoints WHERE id = :id").param("id", endpointId)
                .query(String.class).single()).isEqualTo("OPEN");
        assertThat(deliveries.claimDue(10, "w", Duration.ofSeconds(60))).isEmpty();
        assertThat(statusOf(queued)).isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT attempt_count FROM deliveries WHERE id = :id").param("id", queued)
                .query(Integer.class).single()).isZero();
    }

    /** Replay after the URL was corrected goes to the new URL; the dead letter keeps its history. */
    @Test
    void replayAfterTheUrlWasCorrectedUsesTheNewUrl() throws Exception {
        receiver.respondWith(r -> r.path().equals("/hook") ? Response.status(404) : Response.status(200));
        UUID original = submit("fix-url");
        deliveries.claimDue(10, "w", Duration.ofSeconds(60)).forEach(processor::process);
        assertThat(statusOf(original)).isEqualTo("DEAD_LETTERED");

        mvc.perform(patch("/api/v1/endpoints/" + endpointId).with(account.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + receiver.url("/fixed") + "\"}"))
                .andExpect(status().isOk());
        UUID replay = UUID.fromString(json.readTree(mvc.perform(post("/api/v1/deliveries/" + original + "/replay")
                        .with(account.session())).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString()).get("id").asString());
        deliveries.claimDue(10, "w", Duration.ofSeconds(60)).forEach(processor::process);

        assertThat(statusOf(replay)).isEqualTo("DELIVERED");
        assertThat(receiver.requests()).extracting(TestReceiver.Request::path).containsExactly("/hook", "/fixed");
        assertThat(jdbc.sql("SELECT request_url FROM delivery_attempts WHERE delivery_id = :id").param("id", original)
                .query(String.class).single()).isEqualTo(receiver.url("/hook"));
        assertThat(statusOf(original)).isEqualTo("DEAD_LETTERED");
    }

    /**
     * A replay is queued, then the endpoint is disabled (replay waits, no attempt spent) and then
     * deleted (replay is cancelled with the endpoint's other queued deliveries).
     */
    @Test
    void queuedReplayWaitsWhileDisabledAndIsCancelledWhenTheEndpointIsDeleted() throws Exception {
        receiver.respondWith(r -> Response.status(400));
        UUID original = submit("replay-then-disable");
        deliveries.claimDue(10, "w", Duration.ofSeconds(60)).forEach(processor::process);
        UUID replay = UUID.fromString(json.readTree(mvc.perform(post("/api/v1/deliveries/" + original + "/replay")
                        .with(account.session())).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString()).get("id").asString());
        mvc.perform(patch("/api/v1/endpoints/" + endpointId).with(account.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isOk());
        assertThat(deliveries.claimDue(10, "w", Duration.ofSeconds(60))).isEmpty();
        assertThat(statusOf(replay)).isEqualTo("PENDING");

        endpointService.delete(account.id(), endpointId);
        assertThat(statusOf(replay)).isEqualTo("CANCELLED");
        assertThat(statusOf(original)).isEqualTo("DEAD_LETTERED");
        assertThat(receiver.requests()).hasSize(1);
    }

    static boolean signedWith(String secret, TestReceiver.Request request) throws Exception {
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
