package dev.webhooks.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.webhooks.platform.delivery.DeliveryRepository;
import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import dev.webhooks.platform.support.TestReceiver;
import dev.webhooks.platform.support.TestReceiver.Response;
import dev.webhooks.platform.worker.DeliveryProcessor;
import java.time.Duration;
import java.time.Instant;
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
 * Overview metrics and dashboard filters, computed from real deliveries. Seeds one delivered, one
 * dead-lettered and one retrying delivery inside the window, plus one delivered two days ago.
 */
@IntegrationTest
class DashboardQueriesTest {

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired DeliveryRepository deliveries;
    @Autowired DeliveryProcessor processor;

    TestReceiver receiver;
    Account account;
    UUID ok;
    UUID bad;
    UUID retry;
    UUID old;

    @BeforeEach
    void setUp() throws Exception {
        data.clearDeliveries();
        receiver = new TestReceiver();
        account = data.newAccount();
        String endpoint = json.readTree(mvc.perform(post("/api/v1/endpoints").with(account.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"metrics\",\"url\":\"" + receiver.url("/hook") + "\"}"))
                .andReturn().getResponse().getContentAsString()).get("endpoint").get("id").asString();
        receiver.respondWith(r -> {
            String body = r.bodyText();
            if (body.contains("\"bad\"")) {
                return Response.status(400);
            }
            return Response.status(body.contains("\"retry\"") ? 503 : 200);
        });
        ok = submit("ok", "order.created");
        bad = submit("bad", "order.created");
        retry = submit("retry", "invoice.paid");
        old = submit("old", "order.created");
        deliveries.claimDue(10, "test-worker", Duration.ofSeconds(60)).forEach(processor::process);
        // Move one delivered event and its history two days into the past.
        jdbc.sql("""
                UPDATE deliveries SET created_at = created_at - interval '2 days',
                                      delivered_at = delivered_at - interval '2 days' WHERE id = :id""")
                .param("id", old).update();
        jdbc.sql("UPDATE events SET created_at = created_at - interval '2 days' WHERE id = (SELECT event_id FROM deliveries WHERE id = :id)")
                .param("id", old).update();
        jdbc.sql("UPDATE delivery_attempts SET started_at = started_at - interval '2 days' WHERE delivery_id = :id")
                .param("id", old).update();
    }

    @AfterEach
    void tearDown() {
        receiver.close();
    }

    UUID submit(String key, String type) throws Exception {
        String body = mvc.perform(post("/api/v1/events").header("Authorization", account.bearer())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"" + type + "\",\"payload\":{\"k\":\"" + key + "\"}}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("deliveries").get(0).get("id").asString());
    }

    JsonNode getJson(String url, Account who) throws Exception {
        return json.readTree(mvc.perform(get(url).header("Authorization", who.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void overviewCountsOnlyTheWindowAndComputesDocumentedRates() throws Exception {
        JsonNode day = getJson("/api/v1/metrics/overview?window=24h", account);
        assertThat(day.get("window").asString()).isEqualTo("24h");
        assertThat(day.get("eventsSubmitted").asLong()).isEqualTo(3);
        assertThat(day.get("deliveriesSucceeded").asLong()).isEqualTo(1);
        assertThat(day.get("deliveriesDeadLettered").get("total").asLong()).isEqualTo(1);
        assertThat(day.get("deliveriesDeadLettered").get("byReason").get("NON_RETRYABLE_RESPONSE").asLong()).isEqualTo(1);
        assertThat(day.get("current").get("retrying").asLong()).isEqualTo(1);
        assertThat(day.get("current").get("pending").asLong()).isZero();
        assertThat(day.get("deliverySuccessRate").asDouble()).isEqualTo(0.5);
        assertThat(day.get("attempts").get("succeeded").asLong()).isEqualTo(1);
        assertThat(day.get("attempts").get("failed").asLong()).isEqualTo(2);
        assertThat(day.get("attempts").get("successRate").asDouble()).isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(day.get("attempts").get("latencyP50Ms").isNull()).isFalse();
        assertThat(day.get("recentActivity")).hasSize(4);
        assertThat(day.get("recentActivity").get(0).get("endpointName").asString()).isEqualTo("metrics");

        JsonNode week = getJson("/api/v1/metrics/overview?window=7d", account);
        assertThat(week.get("eventsSubmitted").asLong()).isEqualTo(4);
        assertThat(week.get("deliveriesSucceeded").asLong()).isEqualTo(2);
        assertThat(week.get("deliverySuccessRate").asDouble()).isCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(Instant.parse(week.get("windowStart").asString()))
                .isBetween(Instant.now().minus(Duration.ofDays(7)).minusSeconds(60), Instant.now().minus(Duration.ofDays(7)).plusSeconds(60));
    }

    @Test
    void overviewIsScopedToTheAccountAndRatesAreNullWithoutData() throws Exception {
        JsonNode empty = getJson("/api/v1/metrics/overview", data.newAccount());
        assertThat(empty.get("window").asString()).isEqualTo("24h");
        assertThat(empty.get("eventsSubmitted").asLong()).isZero();
        assertThat(empty.get("deliverySuccessRate").isNull()).isTrue();
        assertThat(empty.get("attempts").get("successRate").isNull()).isTrue();
        assertThat(empty.get("recentActivity")).isEmpty();
    }

    @Test
    void invalidWindowIsRejected() throws Exception {
        mvc.perform(get("/api/v1/metrics/overview?window=30d").header("Authorization", account.bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void deliveriesCanBeFilteredByDeadLetterReasonAndDateRange() throws Exception {
        JsonNode dead = getJson("/api/v1/deliveries?status=DEAD_LETTERED&deadLetterReason=NON_RETRYABLE_RESPONSE", account);
        assertThat(dead.get("items")).extracting(d -> d.get("id").asString()).containsExactly(bad.toString());
        assertThat(getJson("/api/v1/deliveries?deadLetterReason=RETRIES_EXHAUSTED", account).get("items")).isEmpty();

        String since = Instant.now().minus(Duration.ofDays(1)).toString();
        JsonNode recent = getJson("/api/v1/deliveries?from=" + since, account);
        assertThat(recent.get("items")).extracting(d -> d.get("id").asString())
                .containsExactlyInAnyOrder(ok.toString(), bad.toString(), retry.toString());
        JsonNode older = getJson("/api/v1/deliveries?to=" + since, account);
        assertThat(older.get("items")).extracting(d -> d.get("id").asString()).containsExactly(old.toString());
    }

    @Test
    void eventsListShowsPerStatusDeliveryCountsAndFiltersByDate() throws Exception {
        String since = Instant.now().minus(Duration.ofDays(1)).toString();
        JsonNode events = getJson("/api/v1/events?from=" + since, account);
        assertThat(events.get("items")).hasSize(3);
        JsonNode invoice = getJson("/api/v1/events?eventType=invoice.paid", account).get("items").get(0);
        assertThat(invoice.get("deliveryCount").asInt()).isEqualTo(1);
        assertThat(invoice.get("activeCount").asInt()).isEqualTo(1);
        assertThat(invoice.get("deliveredCount").asInt()).isZero();
        JsonNode all = getJson("/api/v1/events", account).get("items");
        assertThat(all).extracting(e -> e.get("deadLetteredCount").asInt()).containsExactlyInAnyOrder(0, 1, 0, 0);
    }
}
