package dev.webhooks.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.webhooks.platform.delivery.ClaimedDelivery;
import dev.webhooks.platform.delivery.DeliveryRepository;
import dev.webhooks.platform.signing.WebhookSigner;
import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import dev.webhooks.platform.support.TestReceiver;
import dev.webhooks.platform.worker.DeliveryProcessor;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** API key lifecycle, signing-secret rotation, write-only secrets, and request-size limits. */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class CredentialsAndSecretsTest {

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired DeliveryRepository deliveries;
    @Autowired DeliveryProcessor processor;

    Account account;
    TestReceiver receiver;

    @BeforeEach
    void setUp() throws Exception {
        data.clearDeliveries();
        account = data.newAccount();
        receiver = new TestReceiver();
    }

    @AfterEach
    void tearDown() {
        receiver.close();
    }

    JsonNode body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    JsonNode createEndpoint(String name) throws Exception {
        return body(mvc.perform(post("/api/v1/endpoints").with(account.session()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"url\":\"" + receiver.url("/hook") + "\"}"))
                .andExpect(status().isCreated()));
    }

    TestReceiver.Request deliverOne(String endpointId, String key) throws Exception {
        mvc.perform(post("/api/v1/events").header("Authorization", account.bearer()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"a.b\",\"payload\":{},\"endpointIds\":[\"" + endpointId + "\"]}"))
                .andExpect(status().isAccepted());
        List<ClaimedDelivery> claimed = deliveries.claimDue(10, "test", Duration.ofSeconds(60));
        assertThat(claimed).hasSize(1);
        int before = receiver.requests().size();
        processor.process(claimed.getFirst());
        return receiver.requests().get(before);
    }

    static List<String> signatures(TestReceiver.Request request) {
        return Arrays.asList(request.header("webhook-signature").split(" "));
    }

    static String expected(String secret, TestReceiver.Request request) {
        return WebhookSigner.sign(secret, request.header("webhook-id"),
                Long.parseLong(request.header("webhook-timestamp")), request.body());
    }

    @Test
    void apiKeyIsShownOnceWorksForIngestionAndStopsWorkingWhenRevoked() throws Exception {
        JsonNode created = body(mvc.perform(post("/api/v1/api-keys").with(account.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"producer\"}"))
                .andExpect(status().isCreated()));
        String key = created.get("key").asString();
        String id = created.get("id").asString();
        assertThat(key).startsWith("whk_").hasSizeGreaterThan(40);
        assertThat(created.get("prefix").asString()).isEqualTo(key.substring(0, 12));

        String list = mvc.perform(get("/api/v1/api-keys").with(account.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains(id).contains(key.substring(0, 12)).doesNotContain(key);
        assertThat(jdbc.sql("SELECT count(*) FROM api_keys WHERE key_prefix = :p AND key_hash IS NOT NULL")
                .param("p", key.substring(0, 12)).query(Integer.class).single()).isEqualTo(1);

        mvc.perform(post("/api/v1/events").header("Authorization", "Bearer " + key).header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventType\":\"a.b\",\"payload\":{}}"))
                .andExpect(status().isAccepted());

        mvc.perform(delete("/api/v1/api-keys/" + id).with(account.session())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/api-keys/" + id).with(account.session())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/events").header("Authorization", "Bearer " + key)).andExpect(status().isUnauthorized());
        assertThat(body(mvc.perform(get("/api/v1/api-keys").with(account.session()))).get("items").findValues("revokedAt")
                .stream().anyMatch(v -> !v.isNull())).isTrue();
    }

    @Test
    void rotationSignsWithBothSecretsDuringTheGracePeriod() throws Exception {
        JsonNode created = createEndpoint("rotating");
        String endpointId = created.get("endpoint").get("id").asString();
        String oldSecret = created.get("secret").asString();

        JsonNode rotated = body(mvc.perform(post("/api/v1/endpoints/" + endpointId + "/rotate-secret").with(account.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gracePeriodHours\":24}"))
                .andExpect(status().isOk()));
        String newSecret = rotated.get("secret").asString();
        assertThat(newSecret).startsWith("whsec_").isNotEqualTo(oldSecret);
        Instant expires = Instant.parse(rotated.get("previousSecretExpiresAt").asString());
        assertThat(Duration.between(Instant.now(), expires)).isBetween(Duration.ofHours(23), Duration.ofHours(24));

        JsonNode endpoint = body(mvc.perform(get("/api/v1/endpoints/" + endpointId).with(account.session())));
        assertThat(endpoint.get("secretPrefix").asString()).isEqualTo(newSecret.substring(0, 12));
        assertThat(endpoint.get("previousSecretExpiresAt").isNull()).isFalse();
        assertThat(endpoint.toString()).doesNotContain(newSecret).doesNotContain(oldSecret);

        TestReceiver.Request request = deliverOne(endpointId, "rot-1");
        assertThat(signatures(request)).containsExactly(expected(newSecret, request), expected(oldSecret, request));
    }

    @Test
    void rotationWithoutGraceDropsTheOldSecretImmediately() throws Exception {
        JsonNode created = createEndpoint("hard-rotate");
        String endpointId = created.get("endpoint").get("id").asString();
        String newSecret = body(mvc.perform(post("/api/v1/endpoints/" + endpointId + "/rotate-secret")
                        .with(account.session()).contentType(MediaType.APPLICATION_JSON).content("{\"gracePeriodHours\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousSecretExpiresAt").isEmpty())).get("secret").asString();

        TestReceiver.Request request = deliverOne(endpointId, "rot-2");
        assertThat(signatures(request)).containsExactly(expected(newSecret, request));

        mvc.perform(post("/api/v1/endpoints/" + endpointId + "/rotate-secret").with(account.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gracePeriodHours\":73}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void expiredPreviousSecretNoLongerSigns() throws Exception {
        JsonNode created = createEndpoint("expired-grace");
        String endpointId = created.get("endpoint").get("id").asString();
        mvc.perform(post("/api/v1/endpoints/" + endpointId + "/rotate-secret").with(account.session()))
                .andExpect(status().isOk());
        jdbc.sql("UPDATE endpoints SET previous_secret_expires_at = now() - interval '1 second' WHERE id = :id")
                .param("id", UUID.fromString(endpointId)).update();

        assertThat(signatures(deliverOne(endpointId, "rot-3"))).hasSize(1);
        mvc.perform(get("/api/v1/endpoints/" + endpointId).with(account.session()))
                .andExpect(jsonPath("$.previousSecretExpiresAt").isEmpty());
    }

    @Test
    void secretsAndKeysNeverReachTheLogs(CapturedOutput output) throws Exception {
        JsonNode created = createEndpoint("quiet");
        String endpointId = created.get("endpoint").get("id").asString();
        String rotated = body(mvc.perform(post("/api/v1/endpoints/" + endpointId + "/rotate-secret").with(account.session())))
                .get("secret").asString();
        String key = body(mvc.perform(post("/api/v1/api-keys").with(account.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"k\"}"))).get("key").asString();
        deliverOne(endpointId, "quiet-1");

        assertThat(output.getAll())
                .doesNotContain(created.get("secret").asString().substring(6))
                .doesNotContain(rotated.substring(6))
                .doesNotContain(key.substring(4))
                .doesNotContain(account.apiKey().substring(4));
    }

    @Test
    void anythingShapedLikeASecretIsMaskedIfSomethingDoesLogIt(CapturedOutput output) {
        LoggerFactory.getLogger(getClass()).warn("accidental whsec_c2VjcmV0LXZhbHVl and whk_abcDEF123_-xyz");
        assertThat(output.getAll()).contains("accidental whsec_**** and whk_****").doesNotContain("c2VjcmV0");
    }

    @Test
    void endpointCountIsCappedPerAccount() throws Exception {
        for (int i = 0; i < 49; i++) {
            jdbc.sql("""
                            INSERT INTO endpoints (account_id, name, url, secret_ciphertext, secret_prefix)
                            VALUES (:a, :n, 'https://example.com/', '\\x00', 'whsec_x')
                            """)
                    .param("a", account.id()).param("n", "bulk-" + i).update();
        }
        createEndpoint("fiftieth");
        mvc.perform(post("/api/v1/endpoints").with(account.session()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"one-too-many\",\"url\":\"" + receiver.url("/hook") + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ENDPOINT_LIMIT_REACHED"));
        // Deleted endpoints do not count.
        jdbc.sql("UPDATE endpoints SET deleted_at = now() WHERE account_id = :a AND name = 'bulk-0'")
                .param("a", account.id()).update();
        createEndpoint("after-delete");
    }

    @Test
    void oversizedBodiesAreRejectedBeforeParsing() throws Exception {
        String big = "{\"eventType\":\"a.b\",\"payload\":{\"x\":\"" + "a".repeat(300 * 1024) + "\"}}";
        mvc.perform(post("/api/v1/events").header("Authorization", account.bearer()).header("Idempotency-Key", "big")
                        .contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        assertThat(jdbc.sql("SELECT count(*) FROM events WHERE account_id = :a").param("a", account.id())
                .query(Integer.class).single()).isZero();
    }

    @Test
    void absurdlyNestedJsonIsRejected() throws Exception {
        String deep = "{\"eventType\":\"a.b\",\"payload\":" + "{\"a\":".repeat(200) + "1" + "}".repeat(200) + "}";
        mvc.perform(post("/api/v1/events").header("Authorization", account.bearer()).header("Idempotency-Key", "deep")
                        .contentType(MediaType.APPLICATION_JSON).content(deep))
                .andExpect(status().isBadRequest());
    }

    @Test
    void problemBodiesCarryTheRequestId() throws Exception {
        mvc.perform(get("/api/v1/endpoints/" + UUID.randomUUID()).with(account.session()).header("X-Request-Id", "trace-abc-123"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Request-Id", "trace-abc-123"))
                .andExpect(jsonPath("$.requestId").value("trace-abc-123"));
    }
}
