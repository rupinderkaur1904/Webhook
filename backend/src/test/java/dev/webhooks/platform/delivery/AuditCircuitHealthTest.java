package dev.webhooks.platform.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.webhooks.platform.circuit.CircuitService;
import dev.webhooks.platform.config.WebhookProperties;
import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import dev.webhooks.platform.support.TestReceiver;
import dev.webhooks.platform.support.TestReceiver.Response;
import dev.webhooks.platform.worker.DeliveryProcessor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Audit (C7): circuit breaker edge cases and endpoint health statistics on real PostgreSQL. */
@IntegrationTest
class AuditCircuitHealthTest {

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired DeliveryRepository deliveries;
    @Autowired DeliveryProcessor processor;
    @Autowired CircuitService circuits;
    @Autowired WebhookProperties properties;

    TestReceiver receiver;
    Account account;
    UUID endpointId;

    @BeforeEach
    void setUp() throws Exception {
        data.clearDeliveries();
        jdbc.sql("UPDATE endpoints SET circuit_state = 'CLOSED', half_open_probe_id = NULL, circuit_retry_at = NULL").update();
        receiver = new TestReceiver();
        account = data.newAccount();
        endpointId = createEndpoint("audit-breaker", receiver.url("/hook"));
    }

    @AfterEach
    void tearDown() {
        receiver.close();
    }

    UUID createEndpoint(String name, String url) throws Exception {
        return UUID.fromString(json.readTree(mvc.perform(post("/api/v1/endpoints").with(account.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\",\"url\":\"" + url + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("endpoint").get("id").asString());
    }

    List<UUID> submit(UUID endpoint, int count) throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String body = mvc.perform(post("/api/v1/events").header("Authorization", account.bearer())
                            .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"eventType\":\"order.created\",\"endpointIds\":[\"" + endpoint + "\"],\"payload\":{\"n\":" + i + "}}"))
                    .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
            ids.add(UUID.fromString(json.readTree(body).get("deliveries").get(0).get("id").asString()));
        }
        return ids;
    }

    int drain() {
        int processed = 0;
        List<ClaimedDelivery> claimed;
        while (!(claimed = deliveries.claimDue(1, "audit", Duration.ofSeconds(60))).isEmpty()) {
            claimed.forEach(processor::process);
            processed++;
        }
        return processed;
    }

    void attemptOne() {
        List<ClaimedDelivery> claimed = deliveries.claimDue(1, "audit", Duration.ofSeconds(60));
        assertThat(claimed).hasSize(1);
        processor.process(claimed.getFirst());
    }

    JsonNode endpoint(UUID id) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/endpoints/" + id).with(account.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    void openCircuit(UUID id) {
        jdbc.sql("""
                        UPDATE endpoints SET circuit_state = 'OPEN', consecutive_failures = 10, circuit_open_count = 1,
                               circuit_opened_at = now(), circuit_retry_at = now() - interval '1 second'
                        WHERE id = :id
                        """).param("id", id).update();
    }

    @Test
    void opensExactlyAtTheThresholdAndNotBefore() throws Exception {
        int threshold = properties.circuit().failureThreshold();
        receiver.respondWith(r -> Response.status(503));
        submit(endpointId, threshold + 3);
        for (int i = 1; i < threshold; i++) {
            attemptOne();
            assertThat(endpoint(endpointId).get("circuit").get("state").asString()).as("after %d failures", i).isEqualTo("CLOSED");
        }
        attemptOne();
        JsonNode circuit = endpoint(endpointId).get("circuit");
        assertThat(circuit.get("state").asString()).isEqualTo("OPEN");
        assertThat(circuit.get("consecutiveFailures").asInt()).isEqualTo(threshold);
        assertThat(deliveries.claimDue(10, "audit", Duration.ofSeconds(60))).isEmpty();
    }

    /** Many maintenance loops and workers at once: exactly one probe is chosen and exactly one is sent. */
    @Test
    void concurrentProbeSelectionAndClaimingYieldExactlyOneProbe() throws Exception {
        List<UUID> queued = submit(endpointId, 6);
        openCircuit(endpointId);

        List<Integer> started = runConcurrently(8, () -> circuits.startProbes(10));
        assertThat(started.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        UUID probe = jdbc.sql("SELECT half_open_probe_id FROM endpoints WHERE id = :id").param("id", endpointId)
                .query(UUID.class).single();
        assertThat(probe).isEqualTo(queued.getFirst());

        List<List<ClaimedDelivery>> claims = runConcurrently(8, () -> deliveries.claimDue(10, "racer", Duration.ofSeconds(60)));
        List<UUID> claimed = claims.stream().flatMap(List::stream).map(ClaimedDelivery::deliveryId).toList();
        assertThat(claimed).containsExactly(probe);
        // While the probe is in flight nothing else is claimed and no second probe is picked.
        assertThat(deliveries.claimDue(10, "late", Duration.ofSeconds(60))).isEmpty();
        assertThat(circuits.startProbes(10)).isZero();
    }

    <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Known limitation (README): changing the URL keeps the circuit. The new URL is not tried until the
     * cool-down ends (probe) or the user closes the circuit by hand.
     */
    @Test
    void changingTheUrlKeepsTheCircuitOpenUntilProbeOrManualReset() throws Exception {
        TestReceiver fixed = new TestReceiver();
        try {
            submit(endpointId, 2);
            jdbc.sql("""
                            UPDATE endpoints SET circuit_state = 'OPEN', consecutive_failures = 10, circuit_open_count = 1,
                                   circuit_opened_at = now(), circuit_retry_at = now() + interval '10 minutes'
                            WHERE id = :id
                            """).param("id", endpointId).update();
            mvc.perform(patch("/api/v1/endpoints/" + endpointId).with(account.session())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + fixed.url("/new") + "\"}"))
                    .andExpect(status().isOk());
            JsonNode after = endpoint(endpointId);
            assertThat(after.get("url").asString()).isEqualTo(fixed.url("/new"));
            assertThat(after.get("circuit").get("state").asString()).isEqualTo("OPEN");
            assertThat(after.get("health").get("status").asString()).isEqualTo("FAILING");
            assertThat(drain()).as("nothing is sent to the new URL while the circuit is open").isZero();

            mvc.perform(post("/api/v1/endpoints/" + endpointId + "/circuit/reset").with(account.session())).andExpect(status().isOk());
            assertThat(drain()).isEqualTo(2);
            assertThat(fixed.requests()).hasSize(2).allSatisfy(r -> assertThat(r.path()).isEqualTo("/new"));
        } finally {
            fixed.close();
        }
    }

    @Test
    void emptyHealthIsNoDataWithNullRatesEverywhere() throws Exception {
        JsonNode detail = endpoint(endpointId).get("health");
        JsonNode window = json.readTree(mvc.perform(get("/api/v1/endpoints/" + endpointId + "/health?window=7d")
                .with(account.session())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (JsonNode h : List.of(detail, window)) {
            assertThat(h.get("status").asString()).isEqualTo("NO_DATA");
            assertThat(h.get("attempts").asLong()).isZero();
            assertThat(h.get("failedAttempts").asLong()).isZero();
            assertThat(h.get("successRate").isNull()).isTrue();
            assertThat(h.get("latencyP50Ms").isNull()).isTrue();
            assertThat(h.get("latencyP95Ms").isNull()).isTrue();
            assertThat(h.get("deadLetters").asLong()).isZero();
            assertThat(h.get("lastSuccessAt").isNull()).isTrue();
            assertThat(h.get("lastFailure").isNull()).isTrue();
        }
        assertThat(window.get("window").asString()).isEqualTo("7d");
        // Disabled wins over everything, including no data.
        mvc.perform(patch("/api/v1/endpoints/" + endpointId).with(account.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isOk());
        assertThat(endpoint(endpointId).get("health").get("status").asString()).isEqualTo("DISABLED");
    }

    @Test
    void windowsLatencyPercentilesAndDeadLettersUseTheDocumentedPopulations() throws Exception {
        receiver.respondWith(r -> Response.status(200));
        submit(endpointId, 10);
        drain();
        // Deterministic latencies 10..100 ms, all inside the last hour.
        List<UUID> attempts = jdbc.sql("SELECT id FROM delivery_attempts WHERE endpoint_id = :id ORDER BY started_at")
                .param("id", endpointId).query(UUID.class).list();
        for (int i = 0; i < attempts.size(); i++) {
            jdbc.sql("UPDATE delivery_attempts SET duration_ms = :ms WHERE id = :id")
                    .param("ms", (i + 1) * 10).param("id", attempts.get(i)).update();
        }
        // Two attempts are 2 hours old: in the 24h window only. One dead letter 2 days old: 7d only.
        jdbc.sql("UPDATE delivery_attempts SET started_at = started_at - interval '2 hours' WHERE id IN (:a, :b)")
                .param("a", attempts.get(0)).param("b", attempts.get(1)).update();
        receiver.respondWith(r -> Response.status(400));
        UUID dead = submit(endpointId, 1).getFirst();
        drain();
        jdbc.sql("UPDATE deliveries SET dead_lettered_at = now() - interval '2 days' WHERE id = :id").param("id", dead).update();
        jdbc.sql("UPDATE delivery_attempts SET started_at = now() - interval '2 days', duration_ms = 999 WHERE delivery_id = :id")
                .param("id", dead).update();

        JsonNode hour = health("1h");
        assertThat(hour.get("attempts").asLong()).isEqualTo(8);
        assertThat(hour.get("failedAttempts").asLong()).isZero();
        assertThat(hour.get("successRate").asDouble()).isEqualTo(1.0);
        assertThat(hour.get("latencyP50Ms").asInt()).isEqualTo(60);   // 30..100: percentile_disc(0.5) = 4th of 8
        assertThat(hour.get("latencyP95Ms").asInt()).isEqualTo(100);
        assertThat(hour.get("deadLetters").asLong()).isZero();
        assertThat(hour.get("status").asString()).isEqualTo("HEALTHY");

        JsonNode day = health("24h");
        assertThat(day.get("attempts").asLong()).isEqualTo(10);
        assertThat(day.get("latencyP50Ms").asInt()).isEqualTo(50);
        assertThat(day.get("latencyP95Ms").asInt()).isEqualTo(100);
        assertThat(day.get("status").asString()).isEqualTo("HEALTHY");

        JsonNode week = health("7d");
        assertThat(week.get("attempts").asLong()).isEqualTo(11);
        assertThat(week.get("failedAttempts").asLong()).isEqualTo(1);
        assertThat(week.get("successRate").asDouble()).isEqualTo(10.0 / 11.0);
        assertThat(week.get("latencyP95Ms").asInt()).as("failed attempts count in the latency population").isEqualTo(999);
        assertThat(week.get("deadLetters").asLong()).isEqualTo(1);
        assertThat(week.get("status").asString()).isEqualTo("DEGRADED");
        // lastFailure is reported over all time, regardless of the window.
        assertThat(hour.get("lastFailure").get("responseStatus").asInt()).isEqualTo(400);
    }

    JsonNode health(String window) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/endpoints/" + endpointId + "/health?window=" + window)
                .header("Authorization", account.bearer())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void listDetailHealthRouteAndOverviewAgree() throws Exception {
        UUID flaky = createEndpoint("audit-flaky", receiver.url("/flaky"));
        UUID quiet = createEndpoint("audit-quiet", receiver.url("/quiet"));
        receiver.respondWith(r -> r.path().equals("/flaky") ? Response.status(503) : Response.status(200));
        submit(endpointId, 3);
        submit(flaky, 2);
        drain();
        openCircuit(flaky);

        JsonNode list = json.readTree(mvc.perform(get("/api/v1/endpoints").with(account.session()))
                .andReturn().getResponse().getContentAsString()).get("items");
        JsonNode overview = json.readTree(mvc.perform(get("/api/v1/metrics/overview?window=24h").with(account.session()))
                .andReturn().getResponse().getContentAsString()).get("endpointHealth");
        assertThat(list.size()).isEqualTo(3);
        for (UUID id : List.of(endpointId, flaky, quiet)) {
            JsonNode fromList = find(list, "id", id).get("health");
            JsonNode fromDetail = endpoint(id).get("health");
            JsonNode fromRoute = json.readTree(mvc.perform(get("/api/v1/endpoints/" + id + "/health").with(account.session()))
                    .andReturn().getResponse().getContentAsString());
            JsonNode fromOverview = find(overview, "endpointId", id).get("health");
            assertThat(fromDetail).as("detail vs list for " + id).isEqualTo(fromList);
            assertThat(fromRoute).as("health route vs list for " + id).isEqualTo(fromList);
            assertThat(fromOverview).as("overview vs list for " + id).isEqualTo(fromList);
            assertThat(find(overview, "endpointId", id).get("circuit")).isEqualTo(find(list, "id", id).get("circuit"));
        }
        assertThat(find(list, "id", flaky).get("health").get("status").asString()).isEqualTo("FAILING");
        assertThat(find(list, "id", quiet).get("health").get("status").asString()).isEqualTo("NO_DATA");
        assertThat(overview.get(0).get("endpointId").asString()).isEqualTo(flaky.toString());
    }

    static JsonNode find(JsonNode array, String field, UUID id) {
        for (JsonNode n : array) {
            if (n.get(field).asString().equals(id.toString())) {
                return n;
            }
        }
        throw new AssertionError(id + " not in " + array);
    }

    /**
     * Health's FAILING rule follows the circuit's failure counter (PLAN §7.5: consecutive_failures >= 5),
     * which ignores 429s and non-retryable answers. An endpoint answering only 429 therefore reports
     * HEALTHY with a 0% success rate until it reaches 10 attempts (DEGRADED). Recorded for the audit:
     * ARCHITECTURE.md said "the last 5 attempts all failed".
     */
    @Test
    void failingFollowsTheCircuitCounterNotRawAttemptFailures() throws Exception {
        receiver.respondWith(r -> new Response(429, Map.of("Retry-After", "1"), "", 0));
        submit(endpointId, 6);
        assertThat(drain()).isEqualTo(6);
        JsonNode h = endpoint(endpointId).get("health");
        assertThat(h.get("attempts").asLong()).isEqualTo(6);
        assertThat(h.get("failedAttempts").asLong()).isEqualTo(6);
        assertThat(h.get("successRate").asDouble()).isZero();
        assertThat(h.get("status").asString()).isEqualTo("HEALTHY");
        assertThat(endpoint(endpointId).get("circuit").get("consecutiveFailures").asInt()).isZero();
    }
}
