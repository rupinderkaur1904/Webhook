package dev.webhooks.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Audit (C1): ingestion edge cases and the API error contract that {@code EventIngestionTest} does not
 * cover: the problem+json shape on framework errors (404/405/415/400), keys not burned by failed
 * requests, concurrent same-key/different-body requests, deleted endpoints, and transaction atomicity
 * when the database fails half way through the fan-out.
 */
@IntegrationTest
class AuditIngestionContractTest {

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    Account account;

    @BeforeEach
    void setUp() {
        account = data.newAccount();
    }

    String endpoint(String name, String eventTypes) throws Exception {
        String body = mvc.perform(post("/api/v1/endpoints").with(account.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"url\":\"http://127.0.0.1:9/" + name + "\",\"eventTypes\":"
                                + eventTypes + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("endpoint").get("id").asString();
    }

    ResultActions submit(String key, String body) throws Exception {
        return mvc.perform(post("/api/v1/events").header("Authorization", account.bearer())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    int count(String sql) {
        return jdbc.sql(sql).param("a", account.id()).query(Integer.class).single();
    }

    /** The documented error shape: problem+json with a stable code and the request id. */
    static ResultMatcher problem(String code) {
        return result -> {
            assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            jsonPath("$.code").value(code).match(result);
            jsonPath("$.requestId").isNotEmpty().match(result);
            jsonPath("$.status").value(result.getResponse().getStatus()).match(result);
            assertThat(result.getResponse().getContentAsString()).doesNotContain("Exception").doesNotContain("at dev.");
        };
    }

    @Test
    void frameworkErrorsUseTheSameProblemShape() throws Exception {
        // Unknown route under /api (authenticated).
        mvc.perform(get("/api/v1/nothing-here").header("Authorization", account.bearer()))
                .andExpect(status().isNotFound()).andExpect(problem("NOT_FOUND"));
        // Unsupported method (session, so authorization lets it through to MVC).
        mvc.perform(put("/api/v1/events").with(account.session()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed()).andExpect(problem("METHOD_NOT_ALLOWED"));
        mvc.perform(delete("/api/v1/events").with(account.session()))
                .andExpect(status().isMethodNotAllowed()).andExpect(problem("METHOD_NOT_ALLOWED"));
        // Unsupported content type.
        mvc.perform(post("/api/v1/events").header("Authorization", account.bearer()).header("Idempotency-Key", "ct")
                        .contentType(MediaType.TEXT_PLAIN).content("{\"eventType\":\"a.b\",\"payload\":{}}"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(problem("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(post("/api/v1/events").header("Authorization", account.bearer()).header("Idempotency-Key", "ct2")
                        .content("{\"eventType\":\"a.b\",\"payload\":{}}"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(problem("UNSUPPORTED_MEDIA_TYPE"));
        // Empty body, wrong JSON types, bad UUIDs and timestamps.
        mvc.perform(post("/api/v1/events").header("Authorization", account.bearer()).header("Idempotency-Key", "e1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest()).andExpect(problem("MALFORMED_REQUEST"));
        submit("e2", "{\"eventType\":\"a.b\",\"payload\":{},\"endpointIds\":[\"not-a-uuid\"]}")
                .andExpect(status().isBadRequest()).andExpect(problem("MALFORMED_REQUEST"));
        submit("e3", "{\"eventType\":\"a.b\",\"payload\":{},\"occurredAt\":\"yesterday\"}")
                .andExpect(status().isBadRequest()).andExpect(problem("MALFORMED_REQUEST"));
        submit("e4", "{\"eventType\":[\"a.b\"],\"payload\":{}}")
                .andExpect(status().isBadRequest()).andExpect(problem("MALFORMED_REQUEST"));
        submit("e5", "[]").andExpect(status().isBadRequest()).andExpect(problem("MALFORMED_REQUEST"));
        // Missing / null / blank / oversized fields.
        submit("v1", "{\"payload\":{}}").andExpect(status().isUnprocessableContent()).andExpect(problem("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("eventType"));
        submit("v2", "{\"eventType\":\"a.b\",\"payload\":null}").andExpect(status().isUnprocessableContent())
                .andExpect(problem("VALIDATION_FAILED")).andExpect(jsonPath("$.errors[0].field").value("payload"));
        submit("v3", "{\"eventType\":\"\",\"payload\":{}}").andExpect(status().isUnprocessableContent())
                .andExpect(problem("VALIDATION_FAILED"));
        submit("v4", "{\"eventType\":\"" + "a".repeat(101) + "\",\"payload\":{}}").andExpect(status().isUnprocessableContent())
                .andExpect(problem("VALIDATION_FAILED"));
        submit("v5", "{\"eventType\":\"a.b\",\"payload\":\"text\"}").andExpect(status().isUnprocessableContent())
                .andExpect(problem("VALIDATION_FAILED"));
        submit("v6", "{\"eventType\":\"a.b\",\"payload\":{},\"endpointIds\":[null]}").andExpect(status().isUnprocessableContent())
                .andExpect(problem("VALIDATION_FAILED"));
        // Header problems.
        mvc.perform(post("/api/v1/events").header("Authorization", account.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"a.b\",\"payload\":{}}"))
                .andExpect(status().isBadRequest()).andExpect(problem("IDEMPOTENCY_KEY_REQUIRED"));
        submit("k".repeat(256), "{\"eventType\":\"a.b\",\"payload\":{}}")
                .andExpect(status().isBadRequest()).andExpect(problem("IDEMPOTENCY_KEY_INVALID"));
        // Query parameter problems.
        mvc.perform(get("/api/v1/events?limit=abc").header("Authorization", account.bearer()))
                .andExpect(status().isBadRequest()).andExpect(problem("INVALID_PARAMETER"));
        mvc.perform(get("/api/v1/events?limit=0").header("Authorization", account.bearer()))
                .andExpect(status().isUnprocessableContent()).andExpect(problem("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/deliveries?status=NOPE").header("Authorization", account.bearer()))
                .andExpect(status().isBadRequest()).andExpect(problem("INVALID_PARAMETER"));
        mvc.perform(get("/api/v1/events/not-a-uuid").header("Authorization", account.bearer()))
                .andExpect(status().isBadRequest()).andExpect(problem("INVALID_PARAMETER"));
        mvc.perform(get("/api/v1/events/" + UUID.randomUUID()).header("Authorization", account.bearer()))
                .andExpect(status().isNotFound()).andExpect(problem("NOT_FOUND"));

        assertThat(count("SELECT count(*) FROM events WHERE account_id = :a")).isZero();
    }

    @Test
    void aFailedRequestDoesNotBurnTheIdempotencyKey() throws Exception {
        String ep = endpoint("burn", "[]");
        jdbc.sql("UPDATE endpoints SET enabled = false WHERE id = :id").param("id", UUID.fromString(ep)).update();
        String body = "{\"eventType\":\"a.b\",\"payload\":{\"n\":1},\"endpointIds\":[\"" + ep + "\"]}";

        submit("retry-me", body).andExpect(status().isUnprocessableContent()).andExpect(problem("ENDPOINT_DISABLED"));
        submit("retry-me", "{\"eventType\":\"BAD TYPE\",\"payload\":{}}").andExpect(status().isUnprocessableContent());
        submit("retry-me", "{broken").andExpect(status().isBadRequest());

        // Once the cause is fixed, the same key and the same body are accepted as new (202, not a replay or 409).
        jdbc.sql("UPDATE endpoints SET enabled = true WHERE id = :id").param("id", UUID.fromString(ep)).update();
        submit("retry-me", body).andExpect(status().isAccepted())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.deliveries.length()").value(1));
        submit("retry-me", body).andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));
        assertThat(count("SELECT count(*) FROM events WHERE account_id = :a")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM deliveries WHERE account_id = :a")).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyWithDifferentBodiesCreatesExactlyOneEvent() throws Exception {
        endpoint("race", "[]");
        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i % 2; // two different bodies
            results.add(pool.submit(() -> {
                start.await();
                return submit("race-diff", "{\"eventType\":\"a.b\",\"payload\":{\"n\":" + n + "}}")
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(statuses).filteredOn(s -> s == 202).hasSize(1);
        assertThat(statuses).allMatch(s -> s == 202 || s == 200 || s == 409);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(threads / 2); // every request with the other body
        assertThat(count("SELECT count(*) FROM events WHERE account_id = :a")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM deliveries WHERE account_id = :a")).isEqualTo(1);
    }

    @Test
    void deletedEndpointsAreNeitherFannedOutToNorAcceptedExplicitly() throws Exception {
        String kept = endpoint("kept", "[\"order.*\"]");
        String deleted = endpoint("deleted", "[]");
        mvc.perform(delete("/api/v1/endpoints/" + deleted).with(account.session())).andExpect(status().isNoContent());

        String body = submit("d1", "{\"eventType\":\"order.paid\",\"payload\":{}}").andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("deliveries").findValuesAsString("endpointId")).containsExactly(kept);

        submit("d2", "{\"eventType\":\"a.b\",\"payload\":{},\"endpointIds\":[\"" + deleted + "\"]}")
                .andExpect(status().isUnprocessableContent()).andExpect(problem("ENDPOINT_NOT_FOUND"));
        // The prefix filter "order.*" does not match "order" itself or "orders.x".
        submit("d3", "{\"eventType\":\"order\",\"payload\":{}}").andExpect(jsonPath("$.deliveries.length()").value(0));
        submit("d4", "{\"eventType\":\"orders.x\",\"payload\":{}}").andExpect(jsonPath("$.deliveries.length()").value(0));
        // Duplicate explicit ids collapse to one delivery.
        submit("d5", "{\"eventType\":\"zzz\",\"payload\":{},\"endpointIds\":[\"" + kept + "\",\"" + kept + "\"]}")
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.deliveries.length()").value(1));
    }

    /**
     * Forces the second delivery insert of a fan-out to fail with a database trigger scoped to one
     * endpoint id: the event and the first delivery must roll back with it, the client gets a generic
     * 500 problem, and the key stays usable.
     */
    @Test
    void aDatabaseFailureMidFanOutLeavesNothingBehind() throws Exception {
        String first = endpoint("ok", "[]");
        String poisoned = endpoint("poisoned", "[]");
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION audit_fail_delivery_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'audit: forced failure for %', NEW.endpoint_id; END $$
                """).update();
        jdbc.sql("CREATE TRIGGER audit_fail_delivery_insert BEFORE INSERT ON deliveries FOR EACH ROW WHEN (NEW.endpoint_id = '"
                + UUID.fromString(poisoned) + "') EXECUTE FUNCTION audit_fail_delivery_insert()").update();
        try {
            String result = submit("atomic", "{\"eventType\":\"a.b\",\"payload\":{}}")
                    .andExpect(status().isInternalServerError())
                    .andExpect(problem("INTERNAL_ERROR"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(result).doesNotContain("audit: forced failure").doesNotContain("deliveries").doesNotContain("SQL");
            assertThat(count("SELECT count(*) FROM events WHERE account_id = :a")).isZero();
            assertThat(count("SELECT count(*) FROM deliveries WHERE account_id = :a")).isZero();
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS audit_fail_delivery_insert ON deliveries").update();
            jdbc.sql("DROP FUNCTION IF EXISTS audit_fail_delivery_insert()").update();
        }
        String ok = submit("atomic", "{\"eventType\":\"a.b\",\"payload\":{}}").andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(ok).get("deliveries").findValuesAsString("endpointId")).containsExactlyInAnyOrder(first, poisoned);
    }

    /**
     * docs/API.md: a repeat "returns 200 with Idempotent-Replayed: true and the original response". The
     * replay re-reads the deliveries, so it reports their current status rather than the original
     * PENDING: the ids and shape are the same, the status is live.
     */
    @Test
    void anIdempotentReplayReportsTheCurrentDeliveryStatus() throws Exception {
        endpoint("live", "[]");
        JsonNode original = json.readTree(submit("live", "{\"eventType\":\"a.b\",\"payload\":{}}")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        jdbc.sql("UPDATE deliveries SET status = 'DELIVERED', next_attempt_at = NULL, delivered_at = now() WHERE account_id = :a")
                .param("a", account.id()).update();
        JsonNode replay = json.readTree(submit("live", "{\"eventType\":\"a.b\",\"payload\":{}}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(replay.get("id")).isEqualTo(original.get("id"));
        assertThat(replay.get("deliveries").get(0).get("id")).isEqualTo(original.get("deliveries").get(0).get("id"));
        assertThat(original.get("deliveries").get(0).get("status").asString()).isEqualTo("PENDING");
        assertThat(replay.get("deliveries").get(0).get("status").asString()).isEqualTo("DELIVERED");
    }

    @Test
    void anEmptyEndpointIdsListTargetsNoEndpoints() throws Exception {
        endpoint("all", "[]");
        submit("empty-list", "{\"eventType\":\"a.b\",\"payload\":{},\"endpointIds\":[]}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.deliveries.length()").value(0));
    }
}
