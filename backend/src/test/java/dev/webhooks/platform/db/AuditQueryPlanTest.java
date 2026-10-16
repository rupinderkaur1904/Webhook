package dev.webhooks.platform.db;

import static org.assertj.core.api.Assertions.assertThat;

import dev.webhooks.platform.circuit.CircuitRepository;
import dev.webhooks.platform.common.Cursor;
import dev.webhooks.platform.config.WebhookProperties;
import dev.webhooks.platform.delivery.DeadLetterReason;
import dev.webhooks.platform.delivery.DeliveryQueryRepository;
import dev.webhooks.platform.delivery.DeliveryRepository;
import dev.webhooks.platform.delivery.DeliveryStatus;
import dev.webhooks.platform.delivery.ReplayService;
import dev.webhooks.platform.endpoint.EndpointRepository;
import dev.webhooks.platform.event.EventRepository;
import dev.webhooks.platform.health.EndpointHealthRepository;
import dev.webhooks.platform.metrics.MetricsRepository;
import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.ScratchDatabase;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Query plans of the hot paths on a realistically sized database (50,000 deliveries for one account,
 * 75,000 attempts, plus other tenants), using the repositories' real SQL: a recording DataSource runs
 * {@code EXPLAIN} with the exact statement and bind values before each execution. A sequential scan
 * of {@code deliveries}, {@code events} or {@code delivery_attempts} on one of these paths is a
 * finding. The plans are written to {@code target/audit-query-plans.txt}.
 */
@IntegrationTest
class AuditQueryPlanTest {

    static final List<String> BIG_TABLES = List.of("deliveries", "events", "delivery_attempts");

    @Autowired DataSource dataSource;
    @Autowired Environment env;
    @Autowired WebhookProperties properties;
    @Autowired ObjectMapper objectMapper;

    /** One recorded statement: the label of the API path, the SQL, its plan and the big tables it scans sequentially. */
    record Recorded(String label, String sql, String plan, List<String> seqScans, Double executionMs) {}

    final List<Recorded> recorded = new ArrayList<>();
    String currentLabel = "";

    @Test
    void hotQueriesUseIndexesOnASeededDatabase() throws Exception {
        try (ScratchDatabase db = new ScratchDatabase(dataSource, env, "audit_plans")) {
            db.migrateTo("5");
            Seeded s = seed(db.jdbc());
            db.jdbc().sql("ANALYZE").update();

            JdbcClient jdbc = JdbcClient.create(explaining(db.dataSource()));
            var queries = new DeliveryQueryRepository(jdbc, objectMapper);
            var deliveries = new DeliveryRepository(jdbc, properties);
            var events = new EventRepository(jdbc);
            var metrics = new MetricsRepository(jdbc);
            var health = new EndpointHealthRepository(jdbc);
            var circuits = new CircuitRepository(jdbc);
            var endpoints = new EndpointRepository(jdbc);
            var replays = new ReplayService(jdbc, queries, properties);
            Instant now = Instant.now();

            run("deliveries list, no filter", () -> queries.list(s.account, filter(null, null, null, null, null), null, 51));
            var page = queries.list(s.account, filter(null, null, null, null, null), null, 51);
            var cursor = new Cursor(page.getLast().createdAt(), page.getLast().id());
            run("deliveries list, next page (cursor)", () -> queries.list(s.account, filter(null, null, null, null, null), cursor, 51));
            run("dead-letter view (status=DEAD_LETTERED)",
                    () -> queries.list(s.account, filter(List.of(DeliveryStatus.DEAD_LETTERED), null, null, null, null), null, 51));
            run("dead-letter view by reason", () -> queries.list(s.account, new DeliveryQueryRepository.Filter(
                    List.of(DeliveryStatus.DEAD_LETTERED), null, null, null, DeadLetterReason.NON_RETRYABLE_RESPONSE, null, null),
                    null, 51));
            run("deliveries list, status RETRYING+PENDING", () -> queries.list(s.account,
                    filter(List.of(DeliveryStatus.RETRYING, DeliveryStatus.PENDING), null, null, null, null), null, 51));
            run("deliveries list, endpointId", () -> queries.list(s.account, filter(null, s.endpoint, null, null, null), null, 51));
            run("deliveries list, endpointId + DEAD_LETTERED", () -> queries.list(s.account,
                    filter(List.of(DeliveryStatus.DEAD_LETTERED), s.endpoint, null, null, null), null, 51));
            run("deliveries list, eventId", () -> queries.list(s.account, filter(null, null, s.event, null, null), null, 51));
            run("deliveries list, eventType (rare type)", () -> queries.list(s.account, filter(null, null, null, "rare.type", null), null, 51));
            run("deliveries list, from/to (one day, 10 days ago)", () -> queries.list(s.account, new DeliveryQueryRepository.Filter(
                    null, null, null, null, null, now.minus(Duration.ofDays(10)), now.minus(Duration.ofDays(9))), null, 51));
            run("delivery detail: find", () -> queries.find(s.account, s.deadLetter));
            run("delivery detail: attempts", () -> queries.attempts(s.deadLetter));
            run("delivery detail: chain", () -> queries.chain(s.account, s.deadLetter));
            run("delivery detail: eventBody", () -> queries.eventBody(s.event));
            run("delivery detail: replay eligibility", () -> replays.eligibility(s.account, s.deadLetter));
            run("events list", () -> events.list(s.account, null, null, null, null, 51));
            run("events list, eventType", () -> events.list(s.account, "rare.type", null, null, null, 51));
            run("overview 24h", () -> metrics.overview(s.account, "24h", Duration.ofHours(24)));
            run("overview 7d", () -> metrics.overview(s.account, "7d", Duration.ofDays(7)));
            run("endpoint health, all endpoints 24h", () -> health.stats(s.account, null, Duration.ofHours(24)));
            run("endpoint health, one endpoint 7d", () -> health.stats(s.account, s.endpoint, Duration.ofDays(7)));
            run("endpoint list", () -> endpoints.list(s.account));
            run("worker: lease reaper scan", () -> deliveries.lockExpiredLeases(100));
            run("worker: circuit probe selection", () -> circuits.startProbes(100, now));
            run("worker: claim", () -> deliveries.claimDue(32, "audit", Duration.ofSeconds(60)));
            run("prometheus queue gauges", () -> {
                var m = new dev.webhooks.platform.observability.AuditQueueMetricsAccess(jdbc);
                return m.read();
            });

            StringBuilder report = new StringBuilder("Server execution time per read-only statement (EXPLAIN ANALYZE, ms):\n");
            recorded.stream().filter(r -> r.executionMs() != null)
                    .forEach(r -> report.append(String.format("%8.2f  %s%n", r.executionMs(), r.label())));
            report.append('\n');
            for (Recorded r : recorded) {
                report.append("### ").append(r.label()).append(r.seqScans().isEmpty() ? "" : "  SEQ SCAN " + r.seqScans())
                        .append('\n').append(r.sql().strip()).append("\n--\n").append(r.plan()).append("\n\n");
            }
            writeReport(report.toString());

            Map<String, List<String>> seqScans = new LinkedHashMap<>();
            // The 7-day event count reads about a quarter of a seed that spans 30 days, so a sequential scan is
            // the planner's cheaper choice there (one aggregate, a few ms); every other path must use an index.
            recorded.stream().filter(r -> !r.seqScans().isEmpty() && !r.label().equals("overview 7d"))
                    .forEach(r -> seqScans.computeIfAbsent(r.label(), k -> new ArrayList<>()).addAll(r.seqScans()));
            assertThat(seqScans).as("hot paths that read a large table sequentially (see target/audit-query-plans.txt)")
                    .isEmpty();
        }
    }

    static DeliveryQueryRepository.Filter filter(List<DeliveryStatus> statuses, UUID endpoint, UUID event, String type,
            DeadLetterReason reason) {
        return new DeliveryQueryRepository.Filter(statuses, endpoint, event, type, reason, null, null);
    }

    void run(String label, Supplier<?> call) {
        currentLabel = label;
        call.get();
    }

    record Seeded(UUID account, UUID endpoint, UUID event, UUID deadLetter) {}

    /** 50k deliveries over 30 days for one account (mostly delivered), 75k attempts, and 10 smaller tenants. */
    static Seeded seed(JdbcClient jdbc) {
        jdbc.sql("""
                INSERT INTO users (email, password_hash)
                SELECT 'tenant' || g || '@example.com', 'x' FROM generate_series(0, 10) g
                """).update();
        UUID account = jdbc.sql("SELECT id FROM users WHERE email = 'tenant0@example.com'").query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO endpoints (account_id, name, url, secret_ciphertext, secret_prefix, circuit_state,
                                       circuit_retry_at, created_at)
                SELECT u.id, 'ep' || g, 'https://example.com/' || g, '\\x00', 'whsec_',
                       CASE WHEN g < 2 AND u.email = 'tenant0@example.com' THEN 'OPEN' ELSE 'CLOSED' END,
                       CASE WHEN g < 2 AND u.email = 'tenant0@example.com' THEN now() + interval '1 minute' END,
                       now() - interval '40 days' + g * interval '1 second'
                FROM users u, generate_series(0, 19) g
                WHERE u.email = 'tenant0@example.com' OR g < 2
                """).update();
        // Events: 50k for the main account, 500 each for the others; one rare type.
        jdbc.sql("""
                INSERT INTO events (id, account_id, event_type, idempotency_key, request_hash, body, created_at)
                SELECT gen_random_uuid(), u.id,
                       CASE WHEN g % 1000 = 0 THEN 'rare.type' ELSE 'order.t' || (g % 10) END,
                       'k' || g, '\\x00', '{"orderId":' || g || '}',
                       now() - (g * interval '30 days' / 50000)
                FROM users u, generate_series(1, 50000) g
                WHERE u.email = 'tenant0@example.com' OR g <= 500
                """).update();
        // One delivery per event to one of the account's endpoints; status by position.
        jdbc.sql("""
                WITH ev AS (
                    SELECT e.id, e.account_id, e.created_at, row_number() OVER (PARTITION BY e.account_id ORDER BY e.created_at) AS n
                    FROM events e
                ), ep AS (
                    SELECT id, account_id, row_number() OVER (PARTITION BY account_id ORDER BY name) - 1 AS k,
                           count(*) OVER (PARTITION BY account_id) AS c
                    FROM endpoints
                ), d AS (
                    SELECT gen_random_uuid() AS id, ev.account_id, ev.id AS event_id, ep.id AS endpoint_id, ev.created_at, ev.n,
                           CASE WHEN ev.n % 100 < 85 THEN 'DELIVERED'
                                WHEN ev.n % 100 < 93 THEN 'DEAD_LETTERED'
                                WHEN ev.n % 100 < 96 THEN 'RETRYING'
                                WHEN ev.n % 100 < 98 THEN 'PENDING'
                                WHEN ev.n % 100 < 99 THEN 'CANCELLED'
                                ELSE 'IN_FLIGHT' END AS status
                    FROM ev JOIN ep ON ep.account_id = ev.account_id AND ep.k = ev.n % ep.c
                )
                INSERT INTO deliveries (id, account_id, event_id, endpoint_id, status, attempt_count, max_attempts,
                                        next_attempt_at, lease_token, lease_owner, lease_expires_at, last_attempt_at,
                                        dead_letter_reason, delivered_at, dead_lettered_at, root_delivery_id, created_at,
                                        updated_at)
                SELECT id, account_id, event_id, endpoint_id, status,
                       CASE status WHEN 'PENDING' THEN 0 WHEN 'DEAD_LETTERED' THEN 3 ELSE 1 END, 8,
                       CASE WHEN status IN ('PENDING', 'RETRYING')
                            THEN now() + ((n % 7) - 3) * interval '1 minute' END,
                       CASE WHEN status = 'IN_FLIGHT' THEN gen_random_uuid() END,
                       CASE WHEN status = 'IN_FLIGHT' THEN 'w' END,
                       CASE WHEN status = 'IN_FLIGHT' THEN now() + interval '1 minute' END,
                       created_at,
                       CASE WHEN status = 'DEAD_LETTERED'
                            THEN (ARRAY['RETRIES_EXHAUSTED', 'NON_RETRYABLE_RESPONSE', 'DESTINATION_BLOCKED'])[1 + n % 3] END,
                       CASE WHEN status = 'DELIVERED' THEN created_at + interval '1 second' END,
                       CASE WHEN status = 'DEAD_LETTERED' THEN created_at + interval '1 hour' END,
                       id, created_at, created_at
                FROM d
                """).update();
        // Attempts: one per delivery that has been tried, three for dead letters.
        jdbc.sql("""
                INSERT INTO delivery_attempts (delivery_id, account_id, endpoint_id, attempt_number, status, outcome,
                                               request_url, response_status, started_at, finished_at, duration_ms)
                SELECT d.id, d.account_id, d.endpoint_id, a,
                       CASE WHEN d.status = 'DELIVERED' THEN 'SUCCEEDED' WHEN d.status = 'IN_FLIGHT' THEN 'STARTED' ELSE 'FAILED' END,
                       CASE WHEN d.status = 'DELIVERED' THEN 'SUCCESS' ELSE 'HTTP_SERVER_ERROR' END,
                       'https://example.com', CASE WHEN d.status = 'DELIVERED' THEN 200 ELSE 503 END,
                       d.created_at + a * interval '1 second', d.created_at + a * interval '1 second', 20 + a
                FROM deliveries d, generate_series(1, 3) a
                WHERE d.status <> 'PENDING' AND a <= d.attempt_count
                """).update();
        UUID endpoint = jdbc.sql("SELECT id FROM endpoints WHERE account_id = :a AND name = 'ep5'").param("a", account)
                .query(UUID.class).single();
        var dead = jdbc.sql("SELECT id, event_id FROM deliveries WHERE account_id = :a AND status = 'DEAD_LETTERED' LIMIT 1")
                .param("a", account).query((rs, n) -> List.of(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
                .single();
        return new Seeded(account, endpoint, dead.get(1), dead.get(0));
    }

    /** A DataSource that runs EXPLAIN with the same statement and bind values before each prepared execution. */
    DataSource explaining(DataSource target) {
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(method, target, args);
                    return result instanceof Connection c ? connection(c) : result;
                });
    }

    private Connection connection(Connection target) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    Object result = invoke(method, target, args);
                    if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement ps) {
                        return statement(target, (String) args[0], ps);
                    }
                    return result;
                });
    }

    private PreparedStatement statement(Connection connection, String sql, PreparedStatement target) {
        List<Object[]> binds = new ArrayList<>();
        List<Method> bindMethods = new ArrayList<>();
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer) {
                        binds.add(args);
                        bindMethods.add(method);
                    }
                    if (method.getName().startsWith("execute") && (args == null || args.length == 0)) {
                        explain(connection, sql, bindMethods, binds);
                    }
                    return invoke(method, target, args);
                });
    }

    private void explain(Connection connection, String sql, List<Method> methods, List<Object[]> binds) throws Exception {
        try (PreparedStatement explain = connection.prepareStatement("EXPLAIN (FORMAT JSON) " + sql)) {
            for (int i = 0; i < binds.size(); i++) {
                methods.get(i).invoke(explain, binds.get(i));
            }
            try (ResultSet rs = explain.executeQuery()) {
                rs.next();
                JsonNode plan = objectMapper.readTree(rs.getString(1)).get(0).get("Plan");
                List<String> seq = new ArrayList<>();
                collectSeqScans(plan, seq);
                recorded.add(new Recorded(currentLabel, sql, text(connection, sql, methods, binds), seq,
                        readOnly(sql) ? executionMs(connection, sql, methods, binds) : null));
            }
        }
    }

    private static String text(Connection connection, String sql, List<Method> methods, List<Object[]> binds)
            throws Exception {
        try (PreparedStatement explain = connection.prepareStatement("EXPLAIN " + sql)) {
            for (int i = 0; i < binds.size(); i++) {
                methods.get(i).invoke(explain, binds.get(i));
            }
            StringBuilder out = new StringBuilder();
            try (ResultSet rs = explain.executeQuery()) {
                while (rs.next()) {
                    out.append(rs.getString(1)).append('\n');
                }
            }
            return out.toString();
        }
    }

    private static boolean readOnly(String sql) {
        String s = sql.strip().toUpperCase();
        return (s.startsWith("SELECT") || s.startsWith("WITH")) && !s.contains("UPDATE") && !s.contains("INSERT");
    }

    /** Best of three EXPLAIN ANALYZE runs (the first warms the cache). */
    private Double executionMs(Connection connection, String sql, List<Method> methods, List<Object[]> binds)
            throws Exception {
        double best = Double.MAX_VALUE;
        for (int run = 0; run < 3; run++) {
            try (PreparedStatement explain = connection.prepareStatement("EXPLAIN (ANALYZE, FORMAT JSON) " + sql)) {
                for (int i = 0; i < binds.size(); i++) {
                    methods.get(i).invoke(explain, binds.get(i));
                }
                try (ResultSet rs = explain.executeQuery()) {
                    rs.next();
                    best = Math.min(best, objectMapper.readTree(rs.getString(1)).get(0).get("Execution Time").asDouble());
                }
            }
        }
        return best;
    }

    private static void collectSeqScans(JsonNode node, List<String> out) {
        if (node == null) {
            return;
        }
        if ("Seq Scan".equals(node.path("Node Type").asString()) && BIG_TABLES.contains(node.path("Relation Name").asString())) {
            out.add(node.path("Relation Name").asString());
        }
        for (JsonNode child : node.path("Plans")) {
            collectSeqScans(child, out);
        }
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static void writeReport(String text) throws IOException {
        Files.writeString(Path.of("target", "audit-query-plans.txt"), text);
    }
}
