package dev.webhooks.platform.delivery;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestReceiver;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * The Stage 1 demonstration flow with the real background worker:
 * submit event, persist, schedule, deliver, persist result, inspect result.
 */
@IntegrationTest
@TestPropertySource(properties = "webhooks.worker.enabled=true")
// Close this context afterwards so its running worker cannot claim deliveries created by other tests.
@DirtiesContext
class WorkerEndToEndTest {

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired ObjectMapper json;

    @Test
    void submittedEventIsDeliveredByTheBackgroundWorker() throws Exception {
        try (TestReceiver receiver = new TestReceiver()) {
            var account = data.newAccount();
            mvc.perform(post("/api/v1/endpoints").with(account.session())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"e2e\",\"url\":\"" + receiver.url("/hook") + "\"}"))
                    .andExpect(status().isCreated());
            String accepted = mvc.perform(post("/api/v1/events").header("Authorization", account.bearer())
                            .header("Idempotency-Key", "e2e-1").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"eventType\":\"payment.succeeded\",\"payload\":{\"amount\":100}}"))
                    .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
            String deliveryId = json.readTree(accepted).get("deliveries").get(0).get("id").asString();

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                String detail = mvc.perform(get("/api/v1/deliveries/" + deliveryId).header("Authorization", account.bearer()))
                        .andReturn().getResponse().getContentAsString();
                var node = json.readTree(detail);
                org.assertj.core.api.Assertions.assertThat(node.get("delivery").get("status").asString()).isEqualTo("DELIVERED");
                org.assertj.core.api.Assertions.assertThat(node.get("attempts").get(0).get("responseStatus").asInt()).isEqualTo(200);
            });
            org.assertj.core.api.Assertions.assertThat(receiver.requests()).hasSize(1);
        }
    }
}
