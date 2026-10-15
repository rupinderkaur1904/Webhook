package dev.webhooks.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@IntegrationTest
class EventIngestionTest {

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    Account account;

    @BeforeEach
    void setUp() {
        account = data.newAccount();
    }

    String endpoint(Account owner, String name, String eventTypes) throws Exception {
        String body = mvc.perform(post("/api/v1/endpoints").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"url\":\"http://127.0.0.1:9/" + name + "\",\"eventTypes\":"
                                + eventTypes + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("endpoint").get("id").asString();
    }

    ResultActions submit(Account owner, String key, String body) throws Exception {
        var request = post("/api/v1/events").header("Authorization", owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    @Test
    void acceptsAndPersistsEventWithPendingDeliveriesBeforeResponding() throws Exception {
        String ep = endpoint(account, "orders", "[]");
        String response = submit(account, "key-1", """
                {"eventType":"order.created","payload":{"orderId":"o-1","amount":4999}}""")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventType").value("order.created"))
                .andExpect(jsonPath("$.idempotencyKey").value("key-1"))
                .andExpect(jsonPath("$.deliveries.length()").value(1))
                .andExpect(jsonPath("$.deliveries[0].endpointId").value(ep))
                .andExpect(jsonPath("$.deliveries[0].status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        UUID eventId = UUID.fromString(json.readTree(response).get("id").asString());
        String body = jdbc.sql("SELECT body FROM events WHERE id = :id").param("id", eventId).query(String.class).single();
        JsonNode envelope = json.readTree(body);
        assertThat(envelope.get("id").asString()).isEqualTo(eventId.toString());
        assertThat(envelope.get("type").asString()).isEqualTo("order.created");
        assertThat(envelope.get("data").get("amount").asInt()).isEqualTo(4999);
        assertThat(jdbc.sql("SELECT count(*) FROM delivery_attempts a JOIN deliveries d ON d.id = a.delivery_id WHERE d.event_id = :id")
                .param("id", eventId).query(Integer.class).single()).isZero();
    }

    @Test
    void sameKeyAndSameRequestReplaysOriginalResponse() throws Exception {
        endpoint(account, "e", "[]");
        String first = submit(account, "same", "{\"eventType\":\"a.b\",\"payload\":{\"x\":1,\"y\":[1,2]}}")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        // Same content with different key order and whitespace.
        String second = submit(account, "same", "{ \"payload\": {\"y\":[1,2], \"x\":1}, \"eventType\":\"a.b\" }")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(second)).isEqualTo(json.readTree(first));
        assertThat(eventCount(account)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM deliveries WHERE account_id = :a").param("a", account.id())
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentRequestConflicts() throws Exception {
        submit(account, "k", "{\"eventType\":\"a.b\",\"payload\":{\"x\":1}}").andExpect(status().isAccepted());
        submit(account, "k", "{\"eventType\":\"a.b\",\"payload\":{\"x\":2}}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        submit(account, "k", "{\"eventType\":\"a.c\",\"payload\":{\"x\":1}}").andExpect(status().isConflict());
        assertThat(eventCount(account)).isEqualTo(1);
    }

    @Test
    void idempotencyKeysAreScopedPerAccount() throws Exception {
        Account other = data.newAccount();
        submit(account, "shared", "{\"eventType\":\"a.b\",\"payload\":{}}").andExpect(status().isAccepted());
        submit(other, "shared", "{\"eventType\":\"a.b\",\"payload\":{\"different\":true}}").andExpect(status().isAccepted());
    }

    @Test
    void concurrentDuplicateSubmissionsCreateExactlyOneEvent() throws Exception {
        endpoint(account, "c", "[]");
        int threads = 12;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Integer> call = () -> {
                start.await();
                return submit(account, "race", "{\"eventType\":\"a.b\",\"payload\":{\"n\":1}}")
                        .andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(call));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(statuses).filteredOn(s -> s == 202).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 200).hasSize(threads - 1);
        assertThat(eventCount(account)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM deliveries WHERE account_id = :a").param("a", account.id())
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void validatesRequests() throws Exception {
        submit(account, null, "{\"eventType\":\"a.b\",\"payload\":{}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        submit(account, "has space", "{\"eventType\":\"a.b\",\"payload\":{}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_INVALID"));
        submit(account, "v1", "{\"eventType\":\"Order Created\",\"payload\":{}}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("eventType"));
        submit(account, "v2", "{\"eventType\":\"a.b\",\"payload\":[1,2]}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("payload"));
        submit(account, "v3", "{\"eventType\":\"a.b\"}")
                .andExpect(status().isUnprocessableContent());
        submit(account, "v4", "{not json")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        assertThat(eventCount(account)).isZero();
    }

    @Test
    void explicitEndpointsMustBeOwnedAndEnabled() throws Exception {
        Account other = data.newAccount();
        String foreign = endpoint(other, "foreign", "[]");
        submit(account, "x1", "{\"eventType\":\"a.b\",\"payload\":{},\"endpointIds\":[\"" + foreign + "\"]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));

        String mine = endpoint(account, "mine", "[]");
        jdbc.sql("UPDATE endpoints SET enabled = false WHERE id = :id").param("id", UUID.fromString(mine)).update();
        submit(account, "x2", "{\"eventType\":\"a.b\",\"payload\":{},\"endpointIds\":[\"" + mine + "\"]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ENDPOINT_DISABLED"));
        // A failed validation must not consume the idempotency key or leave an event behind.
        assertThat(eventCount(account)).isZero();
    }

    @Test
    void fansOutByEventTypeFilter() throws Exception {
        String all = endpoint(account, "all", "[]");
        String exact = endpoint(account, "exact", "[\"order.created\"]");
        String prefix = endpoint(account, "prefix", "[\"order.*\"]");
        endpoint(account, "other", "[\"payment.succeeded\"]");
        String disabled = endpoint(account, "disabled", "[]");
        jdbc.sql("UPDATE endpoints SET enabled = false WHERE id = :id").param("id", UUID.fromString(disabled)).update();

        String body = submit(account, "f1", "{\"eventType\":\"order.created\",\"payload\":{}}")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        List<String> targets = new ArrayList<>();
        json.readTree(body).get("deliveries").forEach(d -> targets.add(d.get("endpointId").asString()));
        assertThat(targets).containsExactlyInAnyOrder(all, exact, prefix);

        body = submit(account, "f2", "{\"eventType\":\"orders_v2.created\",\"payload\":{}}")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        targets.clear();
        json.readTree(body).get("deliveries").forEach(d -> targets.add(d.get("endpointId").asString()));
        assertThat(targets).containsExactly(all);
    }

    @Test
    void eventWithNoMatchingEndpointIsStillRecorded() throws Exception {
        String body = submit(account, "none", "{\"eventType\":\"a.b\",\"payload\":{}}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.deliveries.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/v1/events/" + json.readTree(body).get("id").asString())
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.type").value("a.b"));
    }

    @Test
    void eventsAreListedNewestFirstWithCursorPagination() throws Exception {
        for (int i = 0; i < 5; i++) {
            submit(account, "p" + i, "{\"eventType\":\"a.b\",\"payload\":{\"i\":" + i + "}}").andExpect(status().isAccepted());
        }
        String page1 = mvc.perform(get("/api/v1/events?limit=2").header("Authorization", account.bearer()))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].idempotencyKey").value("p4"))
                .andReturn().getResponse().getContentAsString();
        String cursor = json.readTree(page1).get("nextCursor").asString();
        String page2 = mvc.perform(get("/api/v1/events?limit=2&cursor=" + cursor).header("Authorization", account.bearer()))
                .andExpect(jsonPath("$.items[0].idempotencyKey").value("p2"))
                .andReturn().getResponse().getContentAsString();
        cursor = json.readTree(page2).get("nextCursor").asString();
        mvc.perform(get("/api/v1/events?limit=2&cursor=" + cursor).header("Authorization", account.bearer()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
    }

    private int eventCount(Account owner) {
        return jdbc.sql("SELECT count(*) FROM events WHERE account_id = :a").param("a", owner.id())
                .query(Integer.class).single();
    }
}
