package dev.webhooks.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.PlatformProcess;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestReceiver;
import dev.webhooks.platform.support.TestReceiver.Response;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The platform as operators run it (default profile, environment variables, {@code LOG_FORMAT=ecs}, a
 * non-UTC JVM time zone) driven over real HTTP through a full flow: dashboard login, API key, endpoint
 * creation and secret rotation, one delivery that succeeds and one that is retried, dead-lettered and
 * opens its circuit, lookup by delivery id, logout and SIGTERM. Then:
 * <ul>
 *   <li>every console line is JSON, delivery lines carry the delivery context, request lines the request id;</li>
 *   <li>no secret, key, password, session id, CSRF token, signature or payload appears in the output;</li>
 *   <li>the Prometheus counters moved by exactly what happened, and no label carries an id;</li>
 *   <li>what the health endpoints report.</li>
 * </ul>
 */
@IntegrationTest
class AuditOperationsProcessTest {

    static final Pattern UUID_LIKE = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    static final Pattern SAMPLE = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{(.*)\\})? (\\S+)$");
    static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Autowired Environment env;
    @Autowired TestData data;
    @Autowired ObjectMapper json;

    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final Map<String, String> cookies = new LinkedHashMap<>();
    final Set<String> cookieValuesSeen = new LinkedHashSet<>();
    final List<String> cookieLog = new ArrayList<>();
    PlatformProcess app;

    @Test
    void fullFlowLogsMetricsAndHealthAsOperatorsSeeThem() throws Exception {
        data.clearDeliveries();
        String metricsToken = "audit-ops-metrics-" + UUID.randomUUID();
        String email = "ops-" + UUID.randomUUID() + "@example.com";
        String password = "Audit-Password-" + UUID.randomUUID();
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        String encryptionKey = Base64.getEncoder().encodeToString(keyBytes);
        String marker = "AUDIT-PAYLOAD-" + UUID.randomUUID();

        try (TestReceiver ok = new TestReceiver(); TestReceiver failing = new TestReceiver()) {
            failing.respondWith(r -> Response.status(500));
            Map<String, String> envVars = new LinkedHashMap<>();
            envVars.put("DATABASE_URL", env.getRequiredProperty("spring.datasource.url"));
            envVars.put("DATABASE_USERNAME", env.getRequiredProperty("spring.datasource.username"));
            envVars.put("DATABASE_PASSWORD", env.getRequiredProperty("spring.datasource.password"));
            envVars.put("SECRET_ENCRYPTION_KEY", encryptionKey);
            envVars.put("METRICS_TOKEN", metricsToken);
            envVars.put("BOOTSTRAP_EMAIL", email);
            envVars.put("BOOTSTRAP_PASSWORD", password);
            envVars.put("LOG_FORMAT", "ecs");
            envVars.put("SSRF_ALLOWED_HOSTS", "127.0.0.1");
            envVars.put("CIRCUIT_FAILURE_THRESHOLD", "3");
            envVars.put("WEBHOOKS_RETRY_MAX_ATTEMPTS", "3");
            envVars.put("WEBHOOKS_RETRY_BASE_DELAY", "1s");
            envVars.put("WEBHOOKS_RETRY_MAX_DELAY", "1s");
            envVars.put("WEBHOOKS_WORKER_POLL_INTERVAL", "100ms");
            envVars.put("JAVA_TOOL_OPTIONS", "-Xmx384m");
            app = PlatformProcess.start(List.of("-Duser.timezone=Asia/Kolkata"), envVars, List.of());
            try {
                app.awaitHealthy(Duration.ofSeconds(120));
                Map<String, Integer> health = new TreeMap<>();
                for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
                    health.put(path, send("GET", path, null, Map.of()).statusCode());
                }
                Map<String, Double> before = scrape(metricsToken);

                // Dashboard login (CSRF bootstrap, wrong password first).
                send("GET", "/api/v1/auth/csrf", null, Map.of());
                assertThat(send("POST", "/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"wrong-password-123\"}",
                        csrf()).statusCode()).isEqualTo(401);
                assertThat(send("POST", "/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}",
                        csrf()).statusCode()).isEqualTo(200);
                String apiKey = body(send("POST", "/api/v1/api-keys", "{\"name\":\"ops\"}", csrf())).get("key").asString();
                JsonNode okEndpoint = body(send("POST", "/api/v1/endpoints",
                        "{\"name\":\"ok\",\"url\":\"" + ok.url("/hook") + "\"}", csrf()));
                String okId = okEndpoint.get("endpoint").get("id").asString();
                String rotated = body(send("POST", "/api/v1/endpoints/" + okId + "/rotate-secret", "{\"gracePeriodHours\":1}",
                        csrf())).get("secret").asString();
                JsonNode failingEndpoint = body(send("POST", "/api/v1/endpoints",
                        "{\"name\":\"failing\",\"url\":\"" + failing.url("/hook") + "\"}", csrf()));
                String failingId = failingEndpoint.get("endpoint").get("id").asString();

                JsonNode accepted = body(send("POST", "/api/v1/events", "{\"eventType\":\"order.created\",\"payload\":{\"note\":\""
                        + marker + "\"},\"endpointIds\":[\"" + okId + "\",\"" + failingId + "\"]}",
                        Map.of("Authorization", "Bearer " + apiKey, "Idempotency-Key", "ops-" + UUID.randomUUID())));
                Map<String, String> deliveryByEndpoint = new LinkedHashMap<>();
                accepted.get("deliveries").forEach(d -> deliveryByEndpoint.put(d.get("endpointId").asString(), d.get("id").asString()));

                // An operator finds each delivery by id with an API key (no database access).
                Map<String, String> bearer = Map.of("Authorization", "Bearer " + apiKey);
                await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).until(() ->
                        body(send("GET", "/api/v1/deliveries/" + deliveryByEndpoint.get(okId), null, bearer))
                                .get("delivery").get("status").asString().equals("DELIVERED")
                        && body(send("GET", "/api/v1/deliveries/" + deliveryByEndpoint.get(failingId), null, bearer))
                                .get("delivery").get("status").asString().equals("DEAD_LETTERED"));
                JsonNode dead = body(send("GET", "/api/v1/deliveries/" + deliveryByEndpoint.get(failingId), null, bearer));
                assertThat(dead.get("delivery").get("deadLetterReason").asString()).isEqualTo("RETRIES_EXHAUSTED");
                assertThat(dead.get("attempts")).hasSize(3);
                assertThat(body(send("GET", "/api/v1/endpoints/" + failingId, null, bearer)).get("circuit").get("state").asString())
                        .isEqualTo("OPEN");

                // Gauges are cached for 5 s; wait for a fresh read that sees the open circuit.
                await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofSeconds(1)).until(() ->
                        delta(before, scrape(metricsToken), "webhooks_circuits", "state=\"OPEN\"") >= 1);
                Map<String, Double> after = scrape(metricsToken);
                Map<String, Double> deltas = new TreeMap<>();
                deltas.put("events accepted", delta(before, after, "webhooks_events_received_total", "result=\"accepted\""));
                // Registered as webhooks.deliveries.created; Prometheus reserves the _created suffix, so it is exported as
                // webhooks_deliveries_total.
                deltas.put("deliveries created", delta(before, after, "webhooks_deliveries_total", ""));
                deltas.put("attempts SUCCESS", delta(before, after, "webhooks_attempts_seconds_count", "outcome=\"SUCCESS\""));
                deltas.put("attempts HTTP_SERVER_ERROR",
                        delta(before, after, "webhooks_attempts_seconds_count", "outcome=\"HTTP_SERVER_ERROR\""));
                deltas.put("finished DELIVERED", delta(before, after, "webhooks_deliveries_finished_total",
                        "reason=\"none\",status=\"DELIVERED\""));
                Files.writeString(Path.of("target", "audit-metric-series.txt"), String.join("\n",
                        after.keySet().stream().filter(k -> k.startsWith("webhooks_")).toList()));
                deltas.put("finished DEAD_LETTERED/RETRIES_EXHAUSTED", delta(before, after,
                        "webhooks_deliveries_finished_total", "reason=\"RETRIES_EXHAUSTED\",status=\"DEAD_LETTERED\""));
                deltas.put("retries scheduled", delta(before, after, "webhooks_deliveries_retries_scheduled_total", ""));
                deltas.put("circuit CLOSED->OPEN",
                        delta(before, after, "webhooks_circuit_transitions_total", "from=\"CLOSED\",to=\"OPEN\""));
                deltas.put("circuits OPEN gauge", delta(before, after, "webhooks_circuits", "state=\"OPEN\""));
                assertThat(deltas).containsExactlyInAnyOrderEntriesOf(Map.of(
                        "events accepted", 1.0, "deliveries created", 2.0, "attempts SUCCESS", 1.0,
                        "attempts HTTP_SERVER_ERROR", 3.0, "finished DELIVERED", 1.0,
                        "finished DEAD_LETTERED/RETRIES_EXHAUSTED", 1.0, "retries scheduled", 2.0,
                        "circuit CLOSED->OPEN", 1.0, "circuits OPEN gauge", 1.0));

                // Label cardinality: every label value of every series, no ids anywhere.
                Map<String, Set<String>> labels = labelValues(rawScrape(metricsToken), "webhooks_");
                Map<String, Set<String>> allLabels = labelValues(rawScrape(metricsToken), "");
                assertThat(allLabels.values().stream().flatMap(Set::stream).filter(v -> UUID_LIKE.matcher(v).find()))
                        .as("label values that contain an id").isEmpty();

                // API keys must not become sessions (a session row per call, a cookie that outlives revocation).
                assertThat(cookieLog).as("API-key requests answered with a session cookie").isEmpty();
                assertThat(send("POST", "/api/v1/auth/logout", null, csrf()).statusCode()).isEqualTo(204);
                int exit = app.stop(Duration.ofSeconds(40));

                String output = app.output();
                List<String> lines = output.lines().filter(l -> !l.isBlank()).toList();
                List<String> nonJson = lines.stream().filter(l -> !l.startsWith("{"))
                        .filter(l -> !l.startsWith("Picked up JAVA_TOOL_OPTIONS")).toList();
                List<JsonNode> records = lines.stream().filter(l -> l.startsWith("{")).map(json::readTree).toList();
                List<JsonNode> deliveryLines = records.stream()
                        .filter(r -> r.path("log").path("logger").asString("").endsWith("DeliveryProcessor")
                                || r.path("log.logger").asString("").endsWith("DeliveryProcessor"))
                        .toList();
                long requestIdLines = records.stream().filter(r -> r.has("requestId")).count();

                List<String> sensitive = new ArrayList<>(List.of(password, metricsToken, encryptionKey, marker,
                        apiKey.substring(4), okEndpoint.get("secret").asString().substring(6), rotated.substring(6),
                        failingEndpoint.get("secret").asString().substring(6)));
                sensitive.addAll(cookieValuesSeen);
                for (TestReceiver.Request r : ok.requests()) {
                    for (String sig : r.header("webhook-signature").split(" ")) {
                        sensitive.add(sig.substring(3));
                    }
                }
                List<String> leaked = sensitive.stream().filter(s -> s.length() >= 8 && output.contains(s))
                        .map(s -> s.substring(0, 4) + "…").toList();

                StringBuilder report = new StringBuilder();
                report.append("health status codes: ").append(health).append('\n');
                report.append("exit code after SIGTERM: ").append(exit).append('\n');
                report.append("console lines: ").append(lines.size()).append(", non-JSON: ").append(nonJson).append('\n');
                report.append("delivery log lines: ").append(deliveryLines.size()).append(", lines with requestId: ")
                        .append(requestIdLines).append('\n');
                deliveryLines.forEach(l -> report.append("  ").append(l).append('\n'));
                report.append("metric deltas: ").append(deltas).append('\n');
                report.append("webhooks_* label values: ").append(labels).append('\n');
                report.append("all label keys: ").append(allLabels.keySet()).append('\n');
                report.append("uri label values: ").append(allLabels.get("uri")).append('\n');
                report.append("leaked values: ").append(leaked).append('\n');
                Files.writeString(Path.of("target", "audit-operations.txt"), report.toString());

                assertThat(exit).as("graceful exit on SIGTERM").isIn(0, 143);
                assertThat(nonJson).as("console lines that are not JSON with LOG_FORMAT=ecs").isEmpty();
                assertThat(deliveryLines).isNotEmpty().allSatisfy(l -> assertThat(l.toString())
                        .contains("\"deliveryId\"", "\"eventId\"", "\"endpointId\"", "\"accountId\"", "\"attempt\""));
                assertThat(leaked).as("sensitive values found in the console output").isEmpty();
            } finally {
                app.close();
            }
        }
    }

    Map<String, String> csrf() {
        return Map.of("X-XSRF-TOKEN", cookies.getOrDefault("XSRF-TOKEN", ""), "Content-Type", "application/json");
    }

    HttpResponse<String> send(String method, String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(app.baseUrl() + path)).timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        headers.forEach((k, v) -> {
            if (!(k.equals("Content-Type") && body != null)) {
                b.header(k, v);
            }
        });
        if (!cookies.isEmpty() && !headers.containsKey("Authorization")) {
            b.header("Cookie", String.join("; ", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()));
        }
        HttpResponse<String> response = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        for (String setCookie : response.headers().allValues("set-cookie")) {
            if (headers.containsKey("Authorization") && setCookie.startsWith("WEBHOOKS_SESSION=")) {
                cookieLog.add(method + " " + path);
            }
            String pair = setCookie.split(";", 2)[0];
            int eq = pair.indexOf('=');
            String name = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            if (value.isEmpty()) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
                cookieValuesSeen.add(value);
            }
        }
        return response;
    }

    JsonNode body(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        return json.readTree(response.body());
    }

    String rawScrape(String token) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(app.baseUrl() + "/actuator/prometheus"))
                .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        return r.body();
    }

    /** Series (name + sorted labels without the application tag) to value. */
    Map<String, Double> scrape(String token) throws Exception {
        Map<String, Double> series = new TreeMap<>();
        for (String line : rawScrape(token).lines().toList()) {
            Matcher m = SAMPLE.matcher(line);
            if (line.startsWith("#") || !m.matches()) {
                continue;
            }
            List<String> labels = new ArrayList<>();
            Matcher l = LABEL.matcher(m.group(3) == null ? "" : m.group(3));
            while (l.find()) {
                if (!l.group(1).equals("application")) {
                    labels.add(l.group(1) + "=\"" + l.group(2) + "\"");
                }
            }
            labels.sort(null);
            series.put(m.group(1) + "{" + String.join(",", labels) + "}", Double.parseDouble(m.group(4)));
        }
        return series;
    }

    static double delta(Map<String, Double> before, Map<String, Double> after, String name, String labels) {
        String key = name + "{" + labels + "}";
        return after.getOrDefault(key, 0.0) - before.getOrDefault(key, 0.0);
    }

    static Map<String, Set<String>> labelValues(String scrape, String prefix) {
        Map<String, Set<String>> values = new TreeMap<>();
        for (String line : scrape.lines().toList()) {
            Matcher m = SAMPLE.matcher(line);
            if (line.startsWith("#") || !m.matches() || !m.group(1).startsWith(prefix) || m.group(3) == null) {
                continue;
            }
            Matcher l = LABEL.matcher(m.group(3));
            while (l.find()) {
                values.computeIfAbsent(l.group(1), k -> new java.util.TreeSet<>()).add(l.group(2));
            }
        }
        return values;
    }
}
