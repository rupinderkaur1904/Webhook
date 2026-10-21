package dev.webhooks.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.webhooks.platform.crypto.SecretCipher;
import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@IntegrationTest
class EndpointApiTest {

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired JdbcClient jdbc;
    @Autowired SecretCipher cipher;
    @Autowired ObjectMapper json;

    Account account;

    @BeforeEach
    void setUp() {
        account = data.newAccount();
    }

    JsonNode createEndpoint(Account owner, String name, String url) throws Exception {
        String body = mvc.perform(post("/api/v1/endpoints").with(owner.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"url\":\"" + url + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    @Test
    void createReturnsSecretOnceAndStoresItEncrypted() throws Exception {
        JsonNode created = createEndpoint(account, "orders", "http://127.0.0.1:9/hook");
        String secret = created.get("secret").asString();
        UUID id = UUID.fromString(created.get("endpoint").get("id").asString());
        assertThat(secret).startsWith("whsec_");
        assertThat(created.get("endpoint").get("secretPrefix").asString()).isEqualTo(secret.substring(0, 12));

        mvc.perform(get("/api/v1/endpoints/" + id).header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(jsonPath("$.name").value("orders"));

        byte[] stored = jdbc.sql("SELECT secret_ciphertext FROM endpoints WHERE id = :id").param("id", id)
                .query(byte[].class).single();
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(secret.substring(6));
        assertThat(cipher.decrypt(stored, id)).isEqualTo(secret);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://localhost/hook", "https://10.0.0.1/hook", "https://169.254.169.254/latest/meta-data",
            "https://[::1]/hook", "http://example.com/hook", "https://user:pass@example.com/hook", "file:///etc/passwd"})
    void rejectsUnsafeUrlsAtRegistration(String url) throws Exception {
        mvc.perform(post("/api/v1/endpoints").with(account.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad\",\"url\":\"" + url + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("URL_NOT_ALLOWED"));
    }

    @Test
    void validatesFields() throws Exception {
        mvc.perform(post("/api/v1/endpoints").with(account.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"url\":\"http://127.0.0.1:9/\",\"eventTypes\":[\"Order Created\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    void duplicateNamesConflict() throws Exception {
        createEndpoint(account, "dup", "http://127.0.0.1:9/a");
        mvc.perform(post("/api/v1/endpoints").with(account.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"dup\",\"url\":\"http://127.0.0.1:9/b\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NAME_TAKEN"));
    }

    @Test
    void otherAccountsCannotSeeOrChangeEndpoints() throws Exception {
        String id = createEndpoint(account, "mine", "http://127.0.0.1:9/a").get("endpoint").get("id").asString();
        Account other = data.newAccount();
        mvc.perform(get("/api/v1/endpoints/" + id).header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/endpoints/" + id).with(other.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/endpoints/" + id).with(other.session()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/endpoints/" + id + "/test").with(other.session()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/endpoints").header("Authorization", other.bearer()))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void patchDisablesAndRevalidatesUrl() throws Exception {
        String id = createEndpoint(account, "p", "http://127.0.0.1:9/a").get("endpoint").get("id").asString();
        mvc.perform(patch("/api/v1/endpoints/" + id).with(account.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.url").value("http://127.0.0.1:9/a"));
        mvc.perform(patch("/api/v1/endpoints/" + id).with(account.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://192.168.1.1/x\"}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(post("/api/v1/endpoints/" + id + "/test").with(account.session()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENDPOINT_DISABLED"));
    }

    @Test
    void deleteCancelsQueuedDeliveriesAndHidesEndpoint() throws Exception {
        String id = createEndpoint(account, "d", "http://127.0.0.1:9/a").get("endpoint").get("id").asString();
        String delivery = json.readTree(mvc.perform(post("/api/v1/endpoints/" + id + "/test")
                        .with(account.session()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventType").value("webhook.test"))
                .andReturn().getResponse().getContentAsString()).get("deliveries").get(0).get("id").asString();

        mvc.perform(delete("/api/v1/endpoints/" + id).with(account.session()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/endpoints/" + id).header("Authorization", account.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/deliveries/" + delivery).header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.status").value("CANCELLED"));
    }

    @Test
    void requiresAValidApiKey() throws Exception {
        mvc.perform(get("/api/v1/endpoints")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/endpoints").header("Authorization", "Bearer whk_not-a-real-key"))
                .andExpect(status().isUnauthorized());
        jdbc.sql("UPDATE api_keys SET revoked_at = now() WHERE account_id = :a").param("a", account.id()).update();
        mvc.perform(get("/api/v1/endpoints").header("Authorization", account.bearer()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", startsWith("urn:webhooks:error:")));
    }
}
