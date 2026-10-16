package dev.webhooks.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.PlatformProcess;
import dev.webhooks.platform.support.TcpProxy;
import dev.webhooks.platform.support.TestData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * A database outage as the API sees it: the platform (separate JVM, production defaults, including the
 * connection pool's timeouts) reaches PostgreSQL through a TCP proxy that is cut and then restored.
 * Records what an API client and the health check get during the outage, and requires the API to
 * recover by itself once the database is back (no wedged pool). Results go to
 * {@code target/audit-database-outage.txt}.
 */
@IntegrationTest
class AuditDatabaseOutageTest {

    static final Pattern URL = Pattern.compile("jdbc:postgresql://([^:/]+):(\\d+)/([^?]+).*");

    @Autowired Environment env;
    @Autowired TestData data;

    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void apiFailsDuringADatabaseOutageAndRecoversAfterIt() throws Exception {
        Matcher m = URL.matcher(env.getRequiredProperty("spring.datasource.url"));
        assertThat(m.matches()).isTrue();
        String bearer = data.newAccount().bearer();
        try (TcpProxy proxy = new TcpProxy(m.group(1), Integer.parseInt(m.group(2)))) {
            Map<String, String> envVars = Map.of(
                    "DATABASE_URL", "jdbc:postgresql://127.0.0.1:" + proxy.port() + "/" + m.group(3),
                    "DATABASE_USERNAME", env.getRequiredProperty("spring.datasource.username"),
                    "DATABASE_PASSWORD", env.getRequiredProperty("spring.datasource.password"),
                    "SECRET_ENCRYPTION_KEY", Base64.getEncoder().encodeToString(new byte[32]),
                    "WEBHOOKS_WORKER_ENABLED", "false",
                    "JAVA_TOOL_OPTIONS", "-Xmx256m");
            try (PlatformProcess app = PlatformProcess.start(List.of(), envVars, List.of())) {
                app.awaitHealthy(Duration.ofSeconds(120));
                assertThat(get(app, "/api/v1/endpoints", bearer).status()).isEqualTo(200);

                proxy.cut();
                Map<String, Result> during = new LinkedHashMap<>();
                during.put("GET /api/v1/endpoints (API key)", get(app, "/api/v1/endpoints", bearer));
                during.put("GET /actuator/health", get(app, "/actuator/health", null));
                during.put("GET /actuator/health/liveness", get(app, "/actuator/health/liveness", null));
                during.put("GET /actuator/health/readiness", get(app, "/actuator/health/readiness", null));

                proxy.restore();
                long restoredAt = System.nanoTime();
                await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500))
                        .until(() -> get(app, "/api/v1/endpoints", bearer).status() == 200);
                long recoveryMs = (System.nanoTime() - restoredAt) / 1_000_000;
                Result health = get(app, "/actuator/health", null);

                StringBuilder report = new StringBuilder("During the outage:\n");
                during.forEach((k, v) -> report.append("  ").append(k).append(" -> ").append(v).append('\n'));
                report.append("API answered 200 again ").append(recoveryMs).append(" ms after the database came back\n");
                report.append("health after recovery: ").append(health).append('\n');
                Files.writeString(Path.of("target", "audit-database-outage.txt"), report.toString());

                assertThat(health.status()).isEqualTo(200);
                assertThat(during.get("GET /actuator/health").status()).as("health reports the outage").isEqualTo(503);
                // Not 401 "send a valid API key": the key is fine, the database is not.
                Result api = during.get("GET /api/v1/endpoints (API key)");
                assertThat(api.status()).as("API during the outage: " + api).isEqualTo(503);
                assertThat(api.body()).contains("\"code\":\"DATABASE_UNAVAILABLE\"", "\"requestId\"");
            }
        }
    }

    record Result(int status, long elapsedMs, String body) {
        @Override
        public String toString() {
            return status + " in " + elapsedMs + " ms: " + (body.length() > 300 ? body.substring(0, 300) : body);
        }
    }

    Result get(PlatformProcess app, String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(app.baseUrl() + path)).timeout(Duration.ofSeconds(90));
        if (bearer != null) {
            b.header("Authorization", bearer);
        }
        long start = System.nanoTime();
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Result(r.statusCode(), (System.nanoTime() - start) / 1_000_000, r.body());
    }
}
