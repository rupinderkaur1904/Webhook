package dev.webhooks.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.webhooks.platform.support.PostgresInitializer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * The dashboard is served by the same server as the API, over real HTTP (forwards are not followed
 * by MockMvc). The test classpath has a stand-in index.html and one hashed asset.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ContextConfiguration(initializers = PostgresInitializer.class)
class DashboardServingTest {

    @LocalServerPort int port;
    final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/overview", "/endpoints", "/endpoints/7f1c", "/events", "/events/abc",
            "/deliveries", "/deliveries/0b8e", "/dead-letters", "/api-keys"})
    void clientRoutesReturnTheAppWithoutASession(String path) throws Exception {
        HttpResponse<String> response = get(path);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("test-dashboard");
        assertThat(response.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(
                csp -> assertThat(csp).contains("script-src 'self'"));
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(
                cc -> assertThat(cc).contains("no-cache"));
    }

    @Test
    void hashedAssetsAreCachedForAYear() throws Exception {
        HttpResponse<String> response = get("/assets/app-abc123.js");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(
                cc -> assertThat(cc).contains("max-age=31536000").contains("immutable"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/nope", "/application.yml", "/assets/../application.yml", "/actuator/env",
            "/endpoints/a/b"})
    void otherPathsAreNotServed(String path) throws Exception {
        assertThat(get(path).statusCode()).isIn(400, 401, 403, 404);
    }

    @Test
    void apiDataStillNeedsAuthentication() throws Exception {
        assertThat(get("/api/v1/endpoints").statusCode()).isEqualTo(401);
    }
}
