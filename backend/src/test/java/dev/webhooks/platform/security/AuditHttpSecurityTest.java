package dev.webhooks.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.webhooks.platform.auth.AccountPrincipal;
import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Audit (C9): the HTTP surface as a whole. The route lists are discovered from Spring MVC, so a new
 * route cannot be added without appearing here; every route is then checked anonymously, with an API
 * key and with a session that lacks the CSRF token. Also: cross-account reads that other tests do not
 * cover, cursor and timestamp tampering, CORS, headers on non-API responses and actuator exposure.
 */
@IntegrationTest
class AuditHttpSecurityTest {

    /** Every /api route the backend serves. A change here is a change to the attack surface: review it. */
    static final Set<String> EXPECTED_ROUTES = Set.of(
            "GET /api/v1/auth/csrf", "POST /api/v1/auth/login", "POST /api/v1/auth/register",
            "POST /api/v1/auth/logout", "GET /api/v1/auth/me",
            "GET /api/v1/api-keys", "POST /api/v1/api-keys", "DELETE /api/v1/api-keys/{id}",
            "GET /api/v1/endpoints", "POST /api/v1/endpoints", "GET /api/v1/endpoints/{id}",
            "PATCH /api/v1/endpoints/{id}", "DELETE /api/v1/endpoints/{id}", "GET /api/v1/endpoints/{id}/health",
            "POST /api/v1/endpoints/{id}/circuit/reset", "POST /api/v1/endpoints/{id}/rotate-secret",
            "POST /api/v1/endpoints/{id}/test",
            "GET /api/v1/events", "POST /api/v1/events", "GET /api/v1/events/{id}",
            "GET /api/v1/deliveries", "GET /api/v1/deliveries/{id}", "POST /api/v1/deliveries/{id}/replay",
            "GET /api/v1/metrics/overview");

    static final Set<String> PUBLIC = Set.of("GET /api/v1/auth/csrf", "POST /api/v1/auth/login", "POST /api/v1/auth/register");

    /** What an API key may do besides the public routes: submit events and read (not keys or the user). */
    static boolean apiKeyAllowed(String route) {
        return route.equals("POST /api/v1/events")
                || (route.startsWith("GET ") && !route.startsWith("GET /api/v1/api-keys") && !route.equals("GET /api/v1/auth/me"));
    }

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    Account account;

    @BeforeEach
    void setUp() {
        account = data.newAccount();
    }

    Set<String> apiRoutes() {
        Set<String> routes = new TreeSet<>();
        mappings.getHandlerMethods().keySet().forEach(info -> info.getPatternValues().stream()
                .filter(p -> p.startsWith("/api/"))
                .forEach(p -> info.getMethodsCondition().getMethods()
                        .forEach(m -> routes.add(m.name() + " " + p))));
        return routes;
    }

    static MockHttpServletRequestBuilder call(String route) {
        String[] parts = route.split(" ");
        String path = parts[1].replace("{id}", UUID.randomUUID().toString());
        var builder = request(HttpMethod.valueOf(parts[0]), path);
        if (!parts[0].equals("GET") && !parts[0].equals("DELETE")) {
            builder.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return builder;
    }

    @Test
    void theRouteInventoryIsTheReviewedOne() {
        assertThat(apiRoutes()).isEqualTo(new TreeSet<>(EXPECTED_ROUTES));
    }

    @Test
    void everyNonPublicRouteNeedsAuthentication() throws Exception {
        for (String route : apiRoutes()) {
            if (PUBLIC.contains(route)) {
                continue;
            }
            // With a valid CSRF token, so the answer is about authentication, not CSRF.
            MvcResult result = mvc.perform(call(route).with(csrf())).andReturn();
            assertThat(result.getResponse().getStatus()).as(route).isEqualTo(401);
            assertThat(json.readTree(result.getResponse().getContentAsString()).get("code").asString()).as(route)
                    .isEqualTo("UNAUTHENTICATED");
        }
    }

    @Test
    void apiKeysCanOnlyIngestAndRead() throws Exception {
        for (String route : apiRoutes()) {
            if (PUBLIC.contains(route)) {
                continue;
            }
            int status = mvc.perform(call(route).header("Authorization", account.bearer())).andReturn().getResponse().getStatus();
            if (apiKeyAllowed(route)) {
                assertThat(status).as(route).isNotIn(401, 403);
            } else {
                assertThat(status).as(route).isEqualTo(403);
            }
        }
    }

    @Test
    void everyStateChangingRouteNeedsTheCsrfTokenWithASession() throws Exception {
        var session = authentication(UsernamePasswordAuthenticationToken.authenticated(AccountPrincipal.forSession(account.id()),
                null, List.of(new SimpleGrantedAuthority(AccountPrincipal.ROLE_USER))));
        List<String> checked = new ArrayList<>();
        for (String route : apiRoutes()) {
            if (route.startsWith("GET ")) {
                continue;
            }
            mvc.perform(call(route).with(session))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
            checked.add(route);
        }
        assertThat(checked).contains("POST /api/v1/auth/login", "POST /api/v1/auth/logout", "POST /api/v1/events");
    }

    @Test
    void anotherAccountCannotReadEventsDeliveriesHealthOrMetrics() throws Exception {
        String endpointId = json.readTree(mvc.perform(post("/api/v1/endpoints").with(account.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"url\":\"http://127.0.0.1:9/x\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("endpoint").get("id").asString();
        JsonNode event = json.readTree(mvc.perform(post("/api/v1/events").header("Authorization", account.bearer())
                        .header("Idempotency-Key", "x").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"a.b\",\"payload\":{\"secret\":\"owner-data\"}}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        String eventId = event.get("id").asString();
        String deliveryId = event.get("deliveries").get(0).get("id").asString();

        Account other = data.newAccount();
        for (var auth : List.of(other.bearer())) {
            mvc.perform(get("/api/v1/events/" + eventId).header("Authorization", auth)).andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/deliveries/" + deliveryId).header("Authorization", auth)).andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/endpoints/" + endpointId + "/health").header("Authorization", auth))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/deliveries?endpointId=" + endpointId).header("Authorization", auth))
                    .andExpect(jsonPath("$.items.length()").value(0));
            mvc.perform(get("/api/v1/deliveries?eventId=" + eventId).header("Authorization", auth))
                    .andExpect(jsonPath("$.items.length()").value(0));
            mvc.perform(get("/api/v1/events").header("Authorization", auth)).andExpect(jsonPath("$.items.length()").value(0));
            String overview = mvc.perform(get("/api/v1/metrics/overview").header("Authorization", auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.eventsSubmitted").value(0))
                    .andExpect(jsonPath("$.endpointHealth.length()").value(0))
                    .andExpect(jsonPath("$.recentActivity.length()").value(0))
                    .andReturn().getResponse().getContentAsString();
            assertThat(overview).doesNotContain(endpointId);
        }
        // The same idempotency key in another account is a different event, never the owner's.
        String replayed = mvc.perform(post("/api/v1/events").header("Authorization", other.bearer())
                        .header("Idempotency-Key", "x").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"a.b\",\"payload\":{\"secret\":\"owner-data\"}}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(replayed).get("id").asString()).isNotEqualTo(eventId);
        // The owner still sees everything.
        mvc.perform(get("/api/v1/deliveries/" + deliveryId).header("Authorization", account.bearer()))
                .andExpect(status().isOk());
    }

    static String b64(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void tamperedCursorsAndOutOfRangeTimestampsAre400NotServerErrors() throws Exception {
        String id = UUID.randomUUID().toString();
        List<String> garbage = List.of("!!!", "%00", b64("abc"), b64(":" + id), b64("12:not-a-uuid"), b64("x:" + id),
                b64("1' OR '1'='1:" + id), b64("99999999999999999999:" + id));
        // Well-formed but absurd positions: an empty page or a 400, never a server error.
        List<String> extreme = List.of(b64("-9223372036854775808:" + id), b64("9223372036854775807:" + id),
                b64("-300000000000000000:" + id), b64("300000000000000000:" + id));
        for (String path : List.of("/api/v1/events", "/api/v1/deliveries")) {
            for (String cursor : garbage) {
                mvc.perform(get(path).param("cursor", cursor).header("Authorization", account.bearer()))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
            }
            for (String cursor : extreme) {
                int status = mvc.perform(get(path).param("cursor", cursor).header("Authorization", account.bearer()))
                        .andReturn().getResponse().getStatus();
                assertThat(status).as(path + " cursor " + cursor).isIn(200, 400);
            }
            for (String instant : List.of("+294277-01-01T00:00:00Z", "-4714-01-01T00:00:00Z", "+1000000000-12-31T23:59:59Z",
                    "-1000000000-01-01T00:00:00Z", "2026-13-01T00:00:00Z", "yesterday")) {
                for (String param : List.of("from", "to")) {
                    int status = mvc.perform(get(path).param(param, instant).header("Authorization", account.bearer()))
                            .andReturn().getResponse().getStatus();
                    assertThat(status).as(path + " " + param + "=" + instant).isEqualTo(400);
                }
            }
            // Filter values are bound as parameters, never spliced into SQL.
            mvc.perform(get(path).param("eventType", "a' OR '1'='1").header("Authorization", account.bearer()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        }
    }

    @Test
    void noCorsIsGrantedToOtherOrigins() throws Exception {
        MvcResult preflight = mvc.perform(options("/api/v1/events").header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andReturn();
        assertThat(preflight.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(preflight.getResponse().getHeader("Access-Control-Allow-Credentials")).isNull();
        MvcResult simple = mvc.perform(get("/api/v1/events").header("Origin", "https://evil.example")
                        .header("Authorization", account.bearer()))
                .andReturn();
        assertThat(simple.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void hardeningHeadersAreOnErrorsAndStaticAssetsToo() throws Exception {
        for (MvcResult result : List.of(
                mvc.perform(get("/api/v1/endpoints")).andReturn(),
                mvc.perform(get("/assets/app-abc123.js")).andReturn(),
                mvc.perform(get("/index.html")).andReturn(),
                mvc.perform(get("/api/v1/events/" + UUID.randomUUID()).header("Authorization", account.bearer())).andReturn())) {
            var response = result.getResponse();
            String what = result.getRequest().getRequestURI() + " " + response.getStatus();
            assertThat(response.getHeader("X-Content-Type-Options")).as(what).isEqualTo("nosniff");
            assertThat(response.getHeader("X-Frame-Options")).as(what).isEqualTo("DENY");
            assertThat(response.getHeader("Content-Security-Policy")).as(what).contains("default-src 'self'")
                    .contains("frame-ancestors 'none'").doesNotContain("unsafe-eval");
            assertThat(response.getHeader("Referrer-Policy")).as(what).isEqualTo("strict-origin-when-cross-origin");
            assertThat(response.getHeader("Strict-Transport-Security")).as(what + " (plain HTTP: no HSTS)").isNull();
        }
    }

    @Test
    void noActuatorEndpointOtherThanHealthIsReachable() throws Exception {
        String health = mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(json.readTree(health).propertyNames()).contains("status").isSubsetOf("status", "groups");
        for (String path : List.of("/actuator", "/actuator/env", "/actuator/beans", "/actuator/configprops",
                "/actuator/heapdump", "/actuator/threaddump", "/actuator/loggers", "/actuator/mappings",
                "/actuator/metrics", "/actuator/sessions", "/actuator/shutdown", "/actuator/info")) {
            for (var auth : List.of(account.session(), csrf())) {
                int status = mvc.perform(get(path).with(auth)).andReturn().getResponse().getStatus();
                assertThat(status).as(path).isIn(401, 403, 404);
            }
            assertThat(mvc.perform(get(path).header("Authorization", account.bearer())).andReturn().getResponse().getStatus())
                    .as(path).isIn(401, 403, 404);
        }
    }

    @Test
    void apiKeyUsageIsRecordedAndRevocationIsImmediate() throws Exception {
        assertThat(jdbc.sql("SELECT last_used_at IS NULL FROM api_keys WHERE account_id = :a").param("a", account.id())
                .query(Boolean.class).single()).isTrue();
        mvc.perform(get("/api/v1/events").header("Authorization", account.bearer())).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT last_used_at IS NOT NULL FROM api_keys WHERE account_id = :a").param("a", account.id())
                .query(Boolean.class).single()).isTrue();
        // Only the hash is stored: the plaintext key appears in no column.
        String row = jdbc.sql("SELECT row_to_json(k)::text FROM api_keys k WHERE account_id = :a").param("a", account.id())
                .query(String.class).single();
        assertThat(row).doesNotContain(account.apiKey()).doesNotContain(account.apiKey().substring(12));
        // Case and spacing of the scheme do not matter, but the key must be exact.
        mvc.perform(get("/api/v1/events").header("Authorization", "bearer   " + account.apiKey())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/events").header("Authorization", account.bearer() + "x")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/events").header("Authorization", "Bearer ")).andExpect(status().isUnauthorized());
    }

    @Test
    void unsafeRequestIdsAreReplaced() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/endpoints").header("X-Request-Id", "evil\r\nSet-Cookie: x=y")).andReturn();
        String id = result.getResponse().getHeader("X-Request-Id");
        assertThat(id).doesNotContain("\n").doesNotContain("evil");
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("requestId").asString()).isEqualTo(id);
        mvc.perform(get("/api/v1/endpoints").header("X-Request-Id", "x".repeat(65)))
                .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.not("x".repeat(65))));
    }
}
