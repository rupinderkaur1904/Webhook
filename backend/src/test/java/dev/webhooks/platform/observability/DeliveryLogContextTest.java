package dev.webhooks.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.webhooks.platform.delivery.ClaimedDelivery;
import dev.webhooks.platform.delivery.DeliveryRepository;
import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import dev.webhooks.platform.support.TestReceiver;
import dev.webhooks.platform.worker.DeliveryProcessor;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** Every log line written while a delivery is processed carries the delivery's identifiers (MDC). */
@IntegrationTest
class DeliveryLogContextTest {

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired ObjectMapper json;
    @Autowired DeliveryRepository deliveries;
    @Autowired DeliveryProcessor processor;

    @Test
    void attemptLogsCarryDeliveryContextAndItIsClearedAfterwards() throws Exception {
        data.clearDeliveries();
        Account account = data.newAccount();
        Logger logger = (Logger) LoggerFactory.getLogger(DeliveryProcessor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (TestReceiver receiver = new TestReceiver()) {
            String endpointId = json.readTree(mvc.perform(post("/api/v1/endpoints").with(account.session())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"logs\",\"url\":\"" + receiver.url("/hook") + "\"}"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                    .get("endpoint").get("id").asString();
            mvc.perform(post("/api/v1/events").header("Authorization", account.bearer())
                            .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"eventType\":\"order.created\",\"payload\":{}}"))
                    .andExpect(status().isAccepted());

            List<ClaimedDelivery> claimed = deliveries.claimDue(1, "test-worker", Duration.ofSeconds(60));
            assertThat(claimed).hasSize(1);
            ClaimedDelivery claim = claimed.getFirst();
            processor.process(claim);

            assertThat(appender.list).isNotEmpty().allSatisfy(event -> assertThat(event.getMDCPropertyMap())
                    .containsEntry("deliveryId", claim.deliveryId().toString())
                    .containsEntry("eventId", claim.eventId().toString())
                    .containsEntry("endpointId", endpointId)
                    .containsEntry("accountId", account.id().toString())
                    .containsEntry("attempt", "1"));
            assertThat(MDC.get("deliveryId")).isNull();
        } finally {
            logger.detachAppender(appender);
        }
    }
}
