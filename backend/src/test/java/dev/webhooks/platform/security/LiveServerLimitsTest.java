package dev.webhooks.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.webhooks.platform.support.PostgresInitializer;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.ObjectMapper;

/**
 * Limits checked over real HTTP against the embedded server, with deliberately low rate limits:
 * 429 + Retry-After, and a chunked body (no Content-Length) cut off at the size limit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "webhooks.rate-limit.ingest-burst=3", "webhooks.rate-limit.ingest-per-second=1",
        "webhooks.rate-limit.login-per-minute=3"})
@ActiveProfiles("test")
@ContextConfiguration(initializers = PostgresInitializer.class)
class LiveServerLimitsTest {

    @LocalServerPort int port;
    @Autowired TestData data;
    @Autowired ObjectMapper json;

    final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    HttpResponse<String> submit(Account account, String key, HttpRequest.BodyPublisher body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/events"))
                .header("Authorization", account.bearer())
                .header("Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .POST(body).build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpRequest.BodyPublisher small() {
        return HttpRequest.BodyPublishers.ofString("{\"eventType\":\"a.b\",\"payload\":{}}");
    }

    @Test
    void ingestionOverTheLimitGets429WithRetryAfterPerApiKey() throws Exception {
        Account noisy = data.newAccount();
        Account quiet = data.newAccount();
        for (int i = 0; i < 3; i++) {
            assertThat(submit(noisy, "n-" + i, small()).statusCode()).isEqualTo(202);
        }
        HttpResponse<String> limited = submit(noisy, "n-3", small());
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).hasValueSatisfying(v -> assertThat(Long.parseLong(v)).isPositive());
        assertThat(json.readTree(limited.body()).get("code").asString()).isEqualTo("RATE_LIMITED");

        assertThat(submit(quiet, "q-0", small()).statusCode()).isEqualTo(202);
    }

    @Test
    void chunkedBodyOverTheLimitIsCutOffWith413() throws Exception {
        Account account = data.newAccount();
        byte[] big = ("{\"eventType\":\"a.b\",\"payload\":{\"x\":\"" + "a".repeat(400 * 1024) + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
        // ofInputStream has no known length, so the client sends Transfer-Encoding: chunked.
        HttpResponse<String> response = submit(account, "chunked",
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(big)));
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void repeatedLoginAttemptsAreThrottled() throws Exception {
        HttpRequest csrf = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/csrf")).build();
        String token = json.readTree(http.send(csrf, HttpResponse.BodyHandlers.ofString()).body()).get("token").asString();
        String email = "guess-" + UUID.randomUUID() + "@test.local";
        int[] statuses = new int[4];
        for (int i = 0; i < 4; i++) {
            statuses[i] = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                    .header("Content-Type", "application/json")
                    .header("Cookie", "XSRF-TOKEN=" + token)
                    .header("X-XSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email + "\",\"password\":\"guess-" + i + "\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString()).statusCode();
        }
        assertThat(statuses).containsExactly(401, 401, 401, 429);
    }
}
