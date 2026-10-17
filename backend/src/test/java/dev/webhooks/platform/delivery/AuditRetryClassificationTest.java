package dev.webhooks.platform.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import dev.webhooks.platform.support.TestReceiver;
import dev.webhooks.platform.support.TestReceiver.Response;
import dev.webhooks.platform.worker.DeliveryProcessor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Audit: retry classification over real sockets (docs/PLAN.md §7.4) and the resulting delivery
 * state. Test profile: 3 attempts, 10s base delay, 1s connect / 2s response timeout, 300s max Retry-After.
 */
@IntegrationTest
class AuditRetryClassificationTest {

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired DeliveryRepository deliveries;
    @Autowired DeliveryProcessor processor;

    TestReceiver receiver;
    Account account;
    UUID endpointId;

    @BeforeEach
    void setUp() throws Exception {
        data.clearDeliveries();
        receiver = new TestReceiver();
        account = data.newAccount();
        endpointId = UUID.fromString(json.readTree(mvc.perform(post("/api/v1/endpoints").with(account.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"classify\",\"url\":\"" + receiver.url("/hook") + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("endpoint").get("id").asString());
    }

    @AfterEach
    void tearDown() {
        receiver.close();
    }

    UUID submit(String key) throws Exception {
        String body = mvc.perform(post("/api/v1/events").header("Authorization", account.bearer())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"order.created\",\"payload\":{\"k\":\"" + key + "\"}}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("deliveries").get(0).get("id").asString());
    }

    void pointEndpointAt(String url) {
        jdbc.sql("UPDATE endpoints SET url = :url WHERE id = :id").param("url", url).param("id", endpointId).update();
    }

    long attemptOnce() {
        List<ClaimedDelivery> claimed = deliveries.claimDue(10, "w", Duration.ofSeconds(60));
        assertThat(claimed).hasSize(1);
        long start = System.nanoTime();
        processor.process(claimed.getFirst());
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    Map<String, Object> delivery(UUID id) {
        return jdbc.sql("SELECT status, dead_letter_reason, attempt_count, next_attempt_at FROM deliveries WHERE id = :id")
                .param("id", id).query().singleRow();
    }

    Map<String, Object> attempt(UUID id) {
        return jdbc.sql("""
                        SELECT status, outcome, retryable, response_status, retry_after_seconds
                        FROM delivery_attempts WHERE delivery_id = :id ORDER BY attempt_number DESC LIMIT 1
                        """).param("id", id).query().singleRow();
    }

    @ParameterizedTest(name = "HTTP {0} -> {1}")
    @CsvSource(nullValues = "null", value = {
            "408, RETRYING, null, HTTP_CLIENT_ERROR", "425, RETRYING, null, HTTP_CLIENT_ERROR",
            "429, RETRYING, null, RATE_LIMITED",
            "500, RETRYING, null, HTTP_SERVER_ERROR", "502, RETRYING, null, HTTP_SERVER_ERROR",
            "503, RETRYING, null, HTTP_SERVER_ERROR", "504, RETRYING, null, HTTP_SERVER_ERROR",
            "501, DEAD_LETTERED, NON_RETRYABLE_RESPONSE, HTTP_SERVER_ERROR",
            "505, DEAD_LETTERED, NON_RETRYABLE_RESPONSE, HTTP_SERVER_ERROR",
            "400, DEAD_LETTERED, NON_RETRYABLE_RESPONSE, HTTP_CLIENT_ERROR",
            "401, DEAD_LETTERED, NON_RETRYABLE_RESPONSE, HTTP_CLIENT_ERROR",
            "403, DEAD_LETTERED, NON_RETRYABLE_RESPONSE, HTTP_CLIENT_ERROR",
            "404, DEAD_LETTERED, NON_RETRYABLE_RESPONSE, HTTP_CLIENT_ERROR",
            "409, DEAD_LETTERED, NON_RETRYABLE_RESPONSE, HTTP_CLIENT_ERROR",
            "410, DEAD_LETTERED, NON_RETRYABLE_RESPONSE, HTTP_CLIENT_ERROR",
            "422, DEAD_LETTERED, NON_RETRYABLE_RESPONSE, HTTP_CLIENT_ERROR",
            "200, DELIVERED, null, SUCCESS", "204, DELIVERED, null, SUCCESS"})
    void statusCodesAreClassifiedEndToEnd(int code, String deliveryStatus, String reason, String outcome) throws Exception {
        receiver.respondWith(r -> Response.status(code));
        UUID id = submit("status-" + code);
        Instant before = Instant.now();
        attemptOnce();

        Map<String, Object> d = delivery(id);
        assertThat(d.get("status")).isEqualTo(deliveryStatus);
        assertThat(d.get("dead_letter_reason")).isEqualTo(reason);
        assertThat(d.get("attempt_count")).isEqualTo(1);
        Map<String, Object> a = attempt(id);
        assertThat(a.get("outcome")).isEqualTo(outcome);
        assertThat(a.get("response_status")).isEqualTo(code);
        assertThat(a.get("status")).isEqualTo(code < 300 ? "SUCCEEDED" : "FAILED");
        if (deliveryStatus.equals("RETRYING")) {
            assertThat(a.get("retryable")).isEqualTo(true);
            // First retry: 10s raw, equal jitter -> 5..10s, persisted as next_attempt_at.
            Instant next = ((java.sql.Timestamp) d.get("next_attempt_at")).toInstant();
            assertThat(next).isBetween(before.plusSeconds(4), Instant.now().plusSeconds(11));
        } else if (deliveryStatus.equals("DEAD_LETTERED")) {
            assertThat(a.get("retryable")).isEqualTo(false);
            assertThat(d.get("next_attempt_at")).isNull();
        }
    }

    @Test
    void retryAfterAsHttpDateIsHonouredOn503() throws Exception {
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().plusSeconds(120).atOffset(ZoneOffset.UTC));
        receiver.respondWith(r -> new Response(503, Map.of("Retry-After", date), "", 0));
        UUID id = submit("retry-after-date");
        Instant before = Instant.now();
        attemptOnce();
        Instant next = ((java.sql.Timestamp) delivery(id).get("next_attempt_at")).toInstant();
        assertThat(next).isBetween(before.plusSeconds(115), Instant.now().plusSeconds(121));
        assertThat((Integer) attempt(id).get("retry_after_seconds")).isBetween(115, 120);
    }

    @Test
    void invalidRetryAfterFallsBackToBackoff() throws Exception {
        receiver.respondWith(r -> new Response(429, Map.of("Retry-After", "-30"), "", 0));
        UUID id = submit("retry-after-invalid");
        Instant before = Instant.now();
        attemptOnce();
        Instant next = ((java.sql.Timestamp) delivery(id).get("next_attempt_at")).toInstant();
        assertThat(next).isBetween(before.plusSeconds(4), Instant.now().plusSeconds(11));
        assertThat(attempt(id).get("retry_after_seconds")).isNull();
    }

    @Test
    void unresolvableHostIsADnsErrorAndRetried() throws Exception {
        UUID id = submit("dns");
        pointEndpointAt("https://no-such-host.invalid/hook"); // registration would refuse it; set directly
        attemptOnce();
        assertThat(attempt(id).get("outcome")).isEqualTo("DNS_ERROR");
        assertThat(delivery(id).get("status")).isEqualTo("RETRYING");
    }

    @Test
    void connectionDroppedWithoutAResponseIsRetried() throws Exception {
        try (RawServer server = new RawServer(socket -> {
            readRequest(socket.getInputStream());
            socket.close(); // accepted the request, then hung up without answering
        })) {
            UUID id = submit("reset");
            pointEndpointAt("http://127.0.0.1:" + server.port() + "/hook");
            attemptOnce();
            assertThat(attempt(id).get("outcome")).isEqualTo("CONNECTION_ERROR");
            assertThat(delivery(id).get("status")).isEqualTo("RETRYING");
        }
    }

    @Test
    void connectTimeoutIsATimeoutAndRetried() throws Exception {
        // A listening socket that never accepts, with its accept queue already full: further SYNs
        // are dropped by the kernel, so the client's connect times out (1s in the test profile).
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            List<Socket> fillers = new ArrayList<>();
            try {
                for (int i = 0; i < 8; i++) {
                    Socket s = new Socket();
                    try {
                        s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()), 200);
                        fillers.add(s);
                    } catch (IOException full) {
                        s.close();
                        break;
                    }
                }
                UUID id = submit("connect-timeout");
                pointEndpointAt("http://127.0.0.1:" + listener.getLocalPort() + "/hook");
                long elapsed = attemptOnce();
                assertThat(attempt(id).get("outcome")).isEqualTo("TIMEOUT");
                assertThat(delivery(id).get("status")).isEqualTo("RETRYING");
                assertThat(elapsed).isLessThan(3_000);
            } finally {
                for (Socket s : fillers) {
                    s.close();
                }
            }
        }
    }

    /**
     * The receiver answers 200 at once but its (large) body then stalls. PLAN Stage 1: "only the
     * first 2 KB is read from the socket; the rest is discarded by closing the connection". Expected:
     * SUCCESS / DELIVERED promptly, not a TIMEOUT that would re-send an event the receiver accepted.
     */
    @Test
    void successfulResponseWithAStallingBodyIsDeliveredNotTimedOut() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        try (RawServer server = new RawServer(socket -> {
            readRequest(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 10000000\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write("a".repeat(8192).getBytes(StandardCharsets.US_ASCII));
            out.flush();
            try {
                done.await(20, TimeUnit.SECONDS); // the rest of the body never comes
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        })) {
            UUID id = submit("stalled-body");
            pointEndpointAt("http://127.0.0.1:" + server.port() + "/hook");
            long elapsed = attemptOnce();
            done.countDown();

            assertThat(attempt(id).get("outcome")).isEqualTo("SUCCESS");
            assertThat(delivery(id).get("status")).isEqualTo("DELIVERED");
            assertThat(elapsed).as("does not wait for the body").isLessThan(1_500);
            assertThat(jdbc.sql("SELECT length(response_body_excerpt) FROM delivery_attempts WHERE delivery_id = :id")
                    .param("id", id).query(Integer.class).single()).isEqualTo(2048);
        }
    }

    @Test
    void successfulResponseWithAShortBodyKeepsTheConnectionReusable() throws Exception {
        receiver.respondWith(r -> new Response(200, Map.of(), "ok", 0));
        UUID first = submit("short-1");
        attemptOnce();
        UUID second = submit("short-2");
        attemptOnce();
        assertThat(delivery(first).get("status")).isEqualTo("DELIVERED");
        assertThat(delivery(second).get("status")).isEqualTo("DELIVERED");
        assertThat(jdbc.sql("SELECT response_body_excerpt FROM delivery_attempts WHERE delivery_id = :id")
                .param("id", second).query(String.class).single()).isEqualTo("ok");
    }

    /** Reads an HTTP/1.1 request head and its Content-Length body. */
    static void readRequest(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                return;
            }
            head.append((char) b);
        }
        int length = 0;
        for (String line : head.toString().split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(15).trim());
            }
        }
        in.readNBytes(length);
    }

    interface Handler {
        void handle(Socket socket) throws IOException;
    }

    /** A one-connection-at-a-time raw TCP server on loopback, for responses TestReceiver cannot produce. */
    static final class RawServer implements AutoCloseable {
        private final ServerSocket socket;
        private final Thread thread;

        RawServer(Handler handler) throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            thread = Thread.ofVirtual().start(() -> {
                while (!socket.isClosed()) {
                    try (Socket client = socket.accept()) {
                        handler.handle(client);
                    } catch (IOException e) {
                        // closed, or the client went away
                    }
                }
            });
        }

        int port() {
            return socket.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            socket.close();
            thread.interrupt();
        }
    }
}
