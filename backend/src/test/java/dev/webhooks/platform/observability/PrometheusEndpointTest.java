package dev.webhooks.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** {@code /actuator/prometheus}: only the configured scrape token gets in; application metrics are exported. */
@IntegrationTest
@TestPropertySource(properties = "webhooks.metrics.token=" + PrometheusEndpointTest.TOKEN)
class PrometheusEndpointTest {

    static final String TOKEN = "test-metrics-token-0123456789abcdef";

    @Autowired MockMvc mvc;
    @Autowired TestData data;

    @Test
    void scrapeRequiresTheMetricsToken() throws Exception {
        Account account = data.newAccount();
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + TOKEN + "x"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").header("Authorization", account.bearer()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").with(account.session())).andExpect(status().isForbidden());
        // The metrics token opens nothing else.
        mvc.perform(get("/api/v1/endpoints").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void exportsDeliveryQueueAndCircuitMetrics() throws Exception {
        Account account = data.newAccount();
        mvc.perform(post("/api/v1/events").header("Authorization", account.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"order.created\",\"payload\":{}}"))
                .andExpect(status().isAccepted());

        String body = mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/plain")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains(
                "webhooks_events_received_total{application=\"webhook-platform\",result=\"accepted\"}",
                "webhooks_queue_deliveries{application=\"webhook-platform\",status=\"PENDING\"}",
                "webhooks_queue_due{",
                "webhooks_queue_oldest_due_seconds{",
                "webhooks_circuits{application=\"webhook-platform\",state=\"OPEN\"}",
                "jvm_threads_live_threads",
                "hikaricp_connections_active");
    }
}
