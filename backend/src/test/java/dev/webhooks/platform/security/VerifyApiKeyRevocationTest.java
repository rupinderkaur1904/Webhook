package dev.webhooks.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.webhooks.platform.config.SecurityConfig;
import dev.webhooks.platform.support.PostgresInitializer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.ObjectMapper;

/**
 * Over real HTTP (embedded Tomcat, the real Spring Session JDBC filter): an API key that has been revoked
 * through the dashboard API no longer works, and nothing an API-key response handed out (cookies) can
 * stand in for the key afterwards. Before the S1 fix, each API-key response carried a WEBHOOKS_SESSION
 * cookie that kept authenticating as the API client after revocation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ContextConfiguration(initializers = PostgresInitializer.class)
class VerifyApiKeyRevocationTest {

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper json;

    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    record Reply(int status, Map<String, String> cookies, String body) {}

    Reply send(String method, String path, String body, Map<String, String> headers, Map<String, String> cookies)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        headers.forEach(b::header);
        if (!cookies.isEmpty()) {
            List<String> pairs = new ArrayList<>();
            cookies.forEach((k, v) -> pairs.add(k + "=" + v));
            b.header("Cookie", String.join("; ", pairs));
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        Map<String, String> set = new LinkedHashMap<>();
        for (String h : r.headers().allValues("set-cookie")) {
            String pair = h.split(";", 2)[0];
            int eq = pair.indexOf('=');
            set.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return new Reply(r.statusCode(), set, r.body());
    }

    long sessionRowsFor(UUID principal) {
        return jdbc.sql("SELECT count(*) FROM spring_session WHERE principal_name = :p")
                .param("p", principal.toString()).query(Long.class).single();
    }

    long storedApiClientContexts() {
        // The stored context is serialized; an API-client authentication carries the ROLE_API_CLIENT authority.
        return jdbc.sql("""
                        SELECT count(*) FROM spring_session_attributes
                        WHERE attribute_name = 'SPRING_SECURITY_CONTEXT'
                          AND position(convert_to('ROLE_API_CLIENT', 'UTF8') in attribute_bytes) > 0
                        """).query(Long.class).single();
    }

    @Test
    void revokedKeyAndAnyCookieItsResponsesSetAreRejected() throws Exception {
        String email = "revoke-" + UUID.randomUUID() + "@test.local";
        String password = "Verify-Password-" + UUID.randomUUID();
        UUID userId = jdbc.sql("INSERT INTO users (email, password_hash) VALUES (:e, :h) RETURNING id")
                .param("e", email).param("h", passwordEncoder.encode(password)).query(UUID.class).single();

        // Browser login still creates a session (the fix must not break dashboard sessions).
        Map<String, String> browser = new LinkedHashMap<>(send("GET", "/api/v1/auth/csrf", null, Map.of(), Map.of()).cookies());
        Reply login = send("POST", "/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}",
                Map.of("X-XSRF-TOKEN", browser.get("XSRF-TOKEN")), browser);
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.cookies()).containsKey(SecurityConfig.SESSION_COOKIE);
        browser.putAll(login.cookies());
        Map<String, String> csrf = Map.of("X-XSRF-TOKEN", browser.get("XSRF-TOKEN"));

        Reply created = send("POST", "/api/v1/api-keys", "{\"name\":\"to-revoke\"}", csrf, browser);
        assertThat(created.status()).isEqualTo(201);
        var key = json.readTree(created.body());
        String keyId = key.get("id").asString();
        Map<String, String> bearer = Map.of("Authorization", "Bearer " + key.get("key").asString());
        long sessionsAfterLogin = sessionRowsFor(userId);
        long apiContextsBefore = storedApiClientContexts();

        // Use the key (read and write paths) and collect every cookie the API-key responses set.
        Map<String, String> keyCookies = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            Reply read = send("GET", "/api/v1/endpoints", null, bearer, Map.of());
            assertThat(read.status()).isEqualTo(200);
            keyCookies.putAll(read.cookies());
            Reply write = send("POST", "/api/v1/events", "{\"eventType\":\"verify.revoke\",\"payload\":{}}",
                    Map.of("Authorization", bearer.get("Authorization"), "Idempotency-Key", "rv-" + UUID.randomUUID()), Map.of());
            assertThat(write.status()).isEqualTo(202);
            keyCookies.putAll(write.cookies());
        }
        long sessionsAfterKeyCalls = sessionRowsFor(userId);
        long apiContextsAfterKeyCalls = storedApiClientContexts();

        // Revoke through the dashboard API.
        assertThat(send("DELETE", "/api/v1/api-keys/" + keyId, null, csrf, browser).status()).isEqualTo(204);

        // (a) the key itself
        assertThat(send("GET", "/api/v1/endpoints", null, bearer, Map.of()).status()).as("revoked key, GET").isEqualTo(401);
        assertThat(send("POST", "/api/v1/events", "{\"eventType\":\"verify.revoke\",\"payload\":{}}",
                Map.of("Authorization", bearer.get("Authorization"), "Idempotency-Key", "rv-" + UUID.randomUUID()), Map.of())
                .status()).as("revoked key, POST").isEqualTo(401);
        // (b) any cookie the key's responses set, alone and together with the revoked key
        assertThat(send("GET", "/api/v1/endpoints", null, Map.of(), keyCookies).status())
                .as("cookies from API-key responses " + keyCookies.keySet()).isEqualTo(401);
        assertThat(send("GET", "/api/v1/endpoints", null, bearer, keyCookies).status())
                .as("revoked key plus its cookies").isEqualTo(401);
        // The dashboard session is unaffected by revoking a key.
        assertThat(send("GET", "/api/v1/endpoints", null, Map.of(), browser).status()).isEqualTo(200);

        // And no server-side state was created for the key in the first place.
        assertThat(keyCookies).as("API-key responses must not hand out a session cookie")
                .doesNotContainKey(SecurityConfig.SESSION_COOKIE);
        assertThat(sessionsAfterKeyCalls).as("session rows for the account after API-key calls").isEqualTo(sessionsAfterLogin);
        assertThat(apiContextsAfterKeyCalls).as("stored API-client security contexts").isEqualTo(apiContextsBefore);
    }
}
