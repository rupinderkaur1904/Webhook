package dev.webhooks.platform.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.webhooks.platform.circuit.CircuitService;
import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import dev.webhooks.platform.support.TestReceiver;
import dev.webhooks.platform.support.TestReceiver.Response;
import dev.webhooks.platform.worker.DeliveryProcessor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * The per-endpoint circuit breaker end to end (docs/PLAN.md §7.5), against real PostgreSQL and a real
 * receiver. Test profile: failure threshold 10, base cool-down 30s, max-attempts 3. Waiting for a
 * cool-down or a retry is simulated by moving the timestamp to the past; everything else runs as in
 * production (claim query, result transaction, maintenance loop's probe selection).
 */
@IntegrationTest
class CircuitBreakerFlowTest {

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired DeliveryRepository deliveries;
    @Autowired DeliveryProcessor processor;
    @Autowired CircuitService circuits;

    TestReceiver receiver;
    Account account;
    UUID endpointId;

    @BeforeEach
    void setUp() throws Exception {
        data.clearDeliveries();
        receiver = new TestReceiver();
        account = data.newAccount();
        endpointId = createEndpoint(account, "breaker", receiver.url("/hook"));
    }

    @AfterEach
    void tearDown() {
        receiver.close();
    }

    UUID createEndpoint(Account owner, String name, String url) throws Exception {
        return UUID.fromString(json.readTree(mvc.perform(post("/api/v1/endpoints").with(owner.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"url\":\"" + url + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("endpoint").get("id").asString());
    }

    List<UUID> submit(int count) throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String body = mvc.perform(post("/api/v1/events").header("Authorization", account.bearer())
                            .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"eventType\":\"order.created\",\"payload\":{\"n\":" + i + "}}"))
                    .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
            ids.add(UUID.fromString(json.readTree(body).get("deliveries").get(0).get("id").asString()));
        }
        return ids;
    }

    /** Claims and processes one due delivery at a time, as a worker would; returns how many ran. */
    int drain() {
        int processed = 0;
        List<ClaimedDelivery> claimed;
        while (!(claimed = deliveries.claimDue(1, "test-worker", Duration.ofSeconds(60))).isEmpty()) {
            claimed.forEach(processor::process);
            processed++;
        }
        return processed;
    }

    List<UUID> claimAll() {
        return deliveries.claimDue(50, "test-worker", Duration.ofSeconds(60)).stream()
                .peek(processor::process).map(ClaimedDelivery::deliveryId).toList();
    }

    void makeRetriesDue() {
        jdbc.sql("UPDATE deliveries SET next_attempt_at = now() WHERE endpoint_id = :id AND status = 'RETRYING'")
                .param("id", endpointId).update();
    }

    void endCooldown() {
        jdbc.sql("UPDATE endpoints SET circuit_retry_at = now() - interval '1 second' WHERE id = :id")
                .param("id", endpointId).update();
    }

    JsonNode endpoint() throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/endpoints/" + endpointId).with(account.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    UUID probeId() {
        return jdbc.sql("SELECT half_open_probe_id FROM endpoints WHERE id = :id").param("id", endpointId)
                .query(UUID.class).optional().orElse(null);
    }

    Map<String, Object> delivery(UUID id) {
        return jdbc.sql("SELECT status, attempt_count FROM deliveries WHERE id = :id").param("id", id).query().singleRow();
    }

    @Test
    void opensPausesProbesAndClosesWithoutSpendingTheBacklogsRetryBudget() throws Exception {
        receiver.respondWith(r -> Response.status(500));
        List<UUID> ids = submit(12);

        // 10 consecutive failures open the circuit; the claim query then skips the endpoint.
        assertThat(drain()).isEqualTo(10);
        JsonNode open = endpoint();
        assertThat(open.get("circuit").get("state").asString()).isEqualTo("OPEN");
        assertThat(open.get("circuit").get("consecutiveFailures").asInt()).isEqualTo(10);
        Instant retryAt = Instant.parse(open.get("circuit").get("retryAt").asString());
        assertThat(retryAt).isBetween(Instant.now().plusSeconds(25), Instant.now().plusSeconds(31));
        assertThat(open.get("health").get("status").asString()).isEqualTo("FAILING");
        List<UUID> untouched = ids.subList(10, 12);
        assertThat(untouched).allSatisfy(id -> {
            assertThat(delivery(id).get("status")).isEqualTo("PENDING");
            assertThat(delivery(id).get("attempt_count")).isEqualTo(0);
        });

        // While open nothing is sent, even once retries are due, and nothing is dead-lettered.
        makeRetriesDue();
        assertThat(claimAll()).isEmpty();
        assertThat(circuits.startProbes(10)).as("cool-down not over").isZero();
        assertThat(receiver.requests()).hasSize(10);

        // Cool-down over: exactly one probe, the earliest due delivery. A failed probe reopens for longer.
        endCooldown();
        assertThat(circuits.startProbes(10)).isEqualTo(1);
        assertThat(endpoint().get("circuit").get("state").asString()).isEqualTo("HALF_OPEN");
        UUID firstProbe = probeId();
        assertThat(firstProbe).isEqualTo(untouched.get(0));
        assertThat(claimAll()).containsExactly(firstProbe);
        JsonNode reopened = endpoint().get("circuit");
        assertThat(reopened.get("state").asString()).isEqualTo("OPEN");
        assertThat(Instant.parse(reopened.get("retryAt").asString()))
                .isBetween(Instant.now().plusSeconds(55), Instant.now().plusSeconds(61));

        // Receiver recovers: the next probe succeeds, the circuit closes and the backlog drains.
        receiver.respondWith(r -> Response.status(200));
        endCooldown();
        assertThat(circuits.startProbes(10)).isEqualTo(1);
        assertThat(probeId()).isEqualTo(untouched.get(1));
        assertThat(claimAll()).containsExactly(untouched.get(1));
        JsonNode closed = endpoint().get("circuit");
        assertThat(closed.get("state").asString()).isEqualTo("CLOSED");
        assertThat(closed.get("consecutiveFailures").asInt()).isZero();
        assertThat(closed.get("retryAt").isNull()).isTrue();

        makeRetriesDue();
        assertThat(drain()).isEqualTo(11);
        assertThat(ids).allSatisfy(id -> assertThat(delivery(id).get("status")).isEqualTo("DELIVERED"));
        // 10 failures + failed probe + successful probe + 11 redeliveries. With max-attempts 3 and no
        // breaker, the second probe's 3rd attempt would have been its last.
        assertThat(receiver.requests()).hasSize(23);
        Integer openCount = jdbc.sql("SELECT circuit_open_count FROM endpoints WHERE id = :id")
                .param("id", endpointId).query(Integer.class).single();
        assertThat(openCount).as("a successful probe resets the escalation").isZero();
    }

    @Test
    void rateLimitingNeverOpensTheCircuit() throws Exception {
        receiver.respondWith(r -> new Response(429, Map.of("Retry-After", "1"), "", 0));
        submit(12);
        assertThat(drain()).isEqualTo(12);
        JsonNode circuit = endpoint().get("circuit");
        assertThat(circuit.get("state").asString()).isEqualTo("CLOSED");
        assertThat(circuit.get("consecutiveFailures").asInt()).isZero();
    }

    @Test
    void nonRetryableAnswersProveTheEndpointIsReachable() throws Exception {
        receiver.respondWith(r -> Response.status(500));
        submit(9);
        drain();
        assertThat(endpoint().get("circuit").get("consecutiveFailures").asInt()).isEqualTo(9);
        receiver.respondWith(r -> Response.status(400));
        submit(1);
        drain();
        receiver.respondWith(r -> Response.status(500));
        submit(1);
        drain();
        JsonNode circuit = endpoint().get("circuit");
        assertThat(circuit.get("state").asString()).isEqualTo("CLOSED");
        assertThat(circuit.get("consecutiveFailures").asInt()).isEqualTo(1);
    }

    @Test
    void aHalfOpenCircuitWhoseProbeEndedUndecidedGetsANewProbe() throws Exception {
        receiver.respondWith(r -> Response.status(500));
        List<UUID> ids = submit(2);
        // The probe was dead-lettered by something other than an attempt result (e.g. 429 until exhausted).
        jdbc.sql("""
                        UPDATE endpoints SET circuit_state = 'HALF_OPEN', circuit_open_count = 1,
                               circuit_opened_at = now(), circuit_retry_at = now(), half_open_probe_id = :probe
                        WHERE id = :id
                        """)
                .param("probe", ids.get(0)).param("id", endpointId).update();
        jdbc.sql("UPDATE deliveries SET status = 'DEAD_LETTERED', next_attempt_at = NULL, dead_lettered_at = now(), dead_letter_reason = 'RETRIES_EXHAUSTED' WHERE id = :id")
                .param("id", ids.get(0)).update();
        assertThat(claimAll()).as("nothing but the probe may be sent while half-open").isEmpty();

        assertThat(circuits.startProbes(10)).isEqualTo(1);
        assertThat(probeId()).isEqualTo(ids.get(1));
        assertThat(endpoint().get("circuit").get("state").asString()).isEqualTo("HALF_OPEN");
        // A probe that is still queued is not replaced.
        assertThat(circuits.startProbes(10)).isZero();
        assertThat(probeId()).isEqualTo(ids.get(1));
    }

    /**
     * Regression (independent PR #6 check): open circuits with nothing queued used to fill the probe
     * batch on every pass, so an endpoint that did have work never got its probe.
     */
    @Test
    void idleOpenCircuitsCannotStarveAnEndpointWithWorkOfItsProbe() throws Exception {
        jdbc.sql("UPDATE endpoints SET circuit_state = 'CLOSED', half_open_probe_id = NULL, circuit_retry_at = NULL")
                .update();
        submit(3); // queued for 'breaker' only; the idle endpoints are created afterwards
        String open = """
                UPDATE endpoints SET circuit_state = 'OPEN', consecutive_failures = 10, circuit_open_count = 1,
                       circuit_opened_at = now(), circuit_retry_at = now() - CAST(:ago AS interval)
                WHERE id = :id
                """;
        for (int i = 0; i < 5; i++) {
            UUID idle = createEndpoint(account, "idle-" + i, receiver.url("/idle"));
            jdbc.sql(open).param("ago", "2 hours").param("id", idle).update();
        }
        jdbc.sql(open).param("ago", "1 minute").param("id", endpointId).update();

        assertThat(circuits.startProbes(3)).isEqualTo(1);
        assertThat(endpoint().get("circuit").get("state").asString()).isEqualTo("HALF_OPEN");
        assertThat(probeId()).isNotNull();
    }

    @Test
    void circuitCanBeResetByHandFromTheDashboardOnly() throws Exception {
        jdbc.sql("""
                        UPDATE endpoints SET circuit_state = 'OPEN', consecutive_failures = 12, circuit_open_count = 3,
                               circuit_opened_at = now(), circuit_retry_at = now() + interval '10 minutes'
                        WHERE id = :id
                        """)
                .param("id", endpointId).update();
        String path = "/api/v1/endpoints/" + endpointId + "/circuit/reset";

        mvc.perform(post(path).header("Authorization", account.bearer()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SESSION_REQUIRED"));
        mvc.perform(post(path).with(data.newAccount().session()))
                .andExpect(status().isNotFound());
        assertThat(endpoint().get("circuit").get("state").asString()).isEqualTo("OPEN");

        mvc.perform(post(path).with(account.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.circuit.state").value("CLOSED"))
                .andExpect(jsonPath("$.circuit.consecutiveFailures").value(0))
                .andExpect(jsonPath("$.circuit.retryAt").isEmpty());
        Integer openCount = jdbc.sql("SELECT circuit_open_count FROM endpoints WHERE id = :id")
                .param("id", endpointId).query(Integer.class).single();
        assertThat(openCount).isZero();
    }

    @Test
    void healthReportsWindowStatsAndTheLastFailure() throws Exception {
        receiver.respondWith(r -> Response.status(200));
        submit(3);
        drain();
        receiver.respondWith(r -> Response.status(503));
        submit(1);
        drain();

        mvc.perform(get("/api/v1/endpoints/" + endpointId + "/health?window=1h").header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HEALTHY"))
                .andExpect(jsonPath("$.window").value("1h"))
                .andExpect(jsonPath("$.attempts").value(4))
                .andExpect(jsonPath("$.failedAttempts").value(1))
                .andExpect(jsonPath("$.successRate").value(0.75))
                .andExpect(jsonPath("$.latencyP50Ms").isNumber())
                .andExpect(jsonPath("$.deadLetters").value(0))
                .andExpect(jsonPath("$.lastSuccessAt").isNotEmpty())
                .andExpect(jsonPath("$.lastFailure.outcome").value("HTTP_SERVER_ERROR"))
                .andExpect(jsonPath("$.lastFailure.responseStatus").value(503));
        mvc.perform(get("/api/v1/endpoints/" + endpointId + "/health?window=2y").header("Authorization", account.bearer()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/v1/endpoints/" + endpointId + "/health").with(data.newAccount().session()))
                .andExpect(status().isNotFound());

        // A dead letter in the window makes the endpoint DEGRADED; the overview lists worst first.
        receiver.respondWith(r -> Response.status(410));
        submit(1);
        drain();
        UUID quiet = createEndpoint(account, "quiet", receiver.url("/quiet"));
        jdbc.sql("UPDATE endpoints SET event_types = ARRAY['never.sent'] WHERE id = :id").param("id", quiet).update();
        mvc.perform(get("/api/v1/metrics/overview?window=1h").with(account.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endpointHealth.length()").value(2))
                .andExpect(jsonPath("$.endpointHealth[0].endpointId").value(endpointId.toString()))
                .andExpect(jsonPath("$.endpointHealth[0].health.status").value("DEGRADED"))
                .andExpect(jsonPath("$.endpointHealth[0].health.deadLetters").value(1))
                .andExpect(jsonPath("$.endpointHealth[0].circuit.state").value("CLOSED"))
                .andExpect(jsonPath("$.endpointHealth[1].health.status").value("NO_DATA"));
        mvc.perform(get("/api/v1/endpoints").with(account.session()))
                .andExpect(jsonPath("$.items[?(@.id == '" + endpointId + "')].health.status").value("DEGRADED"));
    }
}
