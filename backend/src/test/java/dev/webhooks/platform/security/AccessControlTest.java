package dev.webhooks.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Who may call what: anonymous, API key (ingest + read), dashboard session (everything), other accounts (404). */
@IntegrationTest
class AccessControlTest {

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired ObjectMapper json;

    Account account;
    String endpointId;

    @BeforeEach
    void setUp() throws Exception {
        account = data.newAccount();
        endpointId = json.readTree(mvc.perform(post("/api/v1/endpoints").with(account.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"acl\",\"url\":\"http://127.0.0.1:9/hook\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("endpoint").get("id").asString();
    }

    /** Routes that change configuration or security state: dashboard session only. */
    static Stream<Arguments> sessionOnlyRoutes() {
        String any = UUID.randomUUID().toString();
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/api/v1/endpoints", "{\"name\":\"x\",\"url\":\"http://127.0.0.1:9/\"}"),
                Arguments.of(HttpMethod.PATCH, "/api/v1/endpoints/{id}", "{\"enabled\":false}"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/endpoints/{id}", null),
                Arguments.of(HttpMethod.POST, "/api/v1/endpoints/{id}/rotate-secret", "{}"),
                Arguments.of(HttpMethod.POST, "/api/v1/endpoints/{id}/test", null),
                Arguments.of(HttpMethod.POST, "/api/v1/deliveries/" + any + "/replay", null),
                Arguments.of(HttpMethod.GET, "/api/v1/api-keys", null),
                Arguments.of(HttpMethod.POST, "/api/v1/api-keys", "{\"name\":\"k\"}"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/api-keys/" + any, null),
                Arguments.of(HttpMethod.GET, "/api/v1/auth/me", null),
                Arguments.of(HttpMethod.POST, "/api/v1/auth/logout", null));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("sessionOnlyRoutes")
    void apiKeysCannotManageTheAccount(HttpMethod method, String path, String body) throws Exception {
        var req = request(method, path.replace("{id}", endpointId)).header("Authorization", account.bearer());
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        mvc.perform(req)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SESSION_REQUIRED"));
        // Nothing changed: the endpoint is still there and enabled.
        mvc.perform(get("/api/v1/endpoints/" + endpointId).header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void apiKeysCanIngestAndRead() throws Exception {
        mvc.perform(post("/api/v1/events").header("Authorization", account.bearer()).header("Idempotency-Key", "acl-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventType\":\"a.b\",\"payload\":{}}"))
                .andExpect(status().isAccepted());
        for (String path : new String[] {"/api/v1/endpoints", "/api/v1/endpoints/" + endpointId, "/api/v1/events",
                "/api/v1/deliveries", "/api/v1/metrics/overview"}) {
            mvc.perform(get(path).header("Authorization", account.bearer())).andExpect(status().isOk());
        }
    }

    @Test
    void anonymousCallersGet401AsProblemJson() throws Exception {
        for (String path : new String[] {"/api/v1/endpoints", "/api/v1/events", "/api/v1/deliveries", "/api/v1/auth/me",
                "/api/v1/api-keys", "/api/v1/metrics/overview"}) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                    .andExpect(jsonPath("$.requestId").isNotEmpty());
        }
        mvc.perform(get("/api/v1/endpoints").header("Authorization", "Bearer whk_not-a-real-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""));
        // Outside /api nothing is served by the backend.
        mvc.perform(get("/internal")).andExpect(status().isUnauthorized());
    }

    @Test
    void registrationIsOffByDefault() throws Exception {
        mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@test.local\",\"password\":\"long-enough-password\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aBearerHeaderOverridesTheSessionSoItCannotBeUsedToSkipCsrf() throws Exception {
        // Session + a garbage Bearer token: CSRF is skipped, but the Bearer token is then checked and fails.
        mvc.perform(post("/api/v1/api-keys").with(account.session()).header("Authorization", "Bearer junk")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"k\"}"))
                .andExpect(status().isUnauthorized());
        // Session + a valid key: the request runs as the API key, which may not manage keys.
        mvc.perform(post("/api/v1/api-keys").with(account.session()).header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"k\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anotherAccountGets404OnEveryRouteThatNamesAResource() throws Exception {
        Account other = data.newAccount();
        String keyId = json.readTree(mvc.perform(post("/api/v1/api-keys").with(account.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"mine\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();

        mvc.perform(get("/api/v1/endpoints/" + endpointId).with(other.session())).andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/endpoints/" + endpointId).with(other.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/endpoints/" + endpointId + "/rotate-secret").with(other.session()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/endpoints/" + endpointId + "/test").with(other.session())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/endpoints/" + endpointId).with(other.session())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/api-keys/" + keyId).with(other.session())).andExpect(status().isNotFound());
        JsonNode otherKeys = json.readTree(mvc.perform(get("/api/v1/api-keys").with(other.session()))
                .andReturn().getResponse().getContentAsString());
        assertThat(otherKeys.get("items").findValuesAsString("id")).doesNotContain(keyId);

        // The owner's resources are untouched.
        mvc.perform(get("/api/v1/endpoints/" + endpointId).with(account.session()))
                .andExpect(jsonPath("$.enabled").value(true));
    }
}
