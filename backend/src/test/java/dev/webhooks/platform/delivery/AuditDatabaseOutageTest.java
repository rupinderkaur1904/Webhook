package dev.webhooks.platform.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.webhooks.platform.WebhookPlatformApplication;
import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.PostgresInitializer;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import dev.webhooks.platform.support.TestReceiver;
import dev.webhooks.platform.support.TestReceiver.Response;
import dev.webhooks.platform.worker.DeliveryWorker;
import dev.webhooks.platform.worker.LeaseReaper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Audit, Phase E: database unavailability as seen by a real worker. A second platform instance
 * (worker enabled) reaches PostgreSQL only through a TCP proxy that the test can cut, which closes
 * every open connection and refuses new ones, i.e. a network partition or a database restart from
 * that instance's point of view. The test's own context talks to the database directly to observe
 * and to play the role of a healthy instance's reaper.
 */
@IntegrationTest
class AuditDatabaseOutageTest {

    static TcpProxy proxy;
    static ConfigurableApplicationContext instance;

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired LeaseReaper reaper;

    TestReceiver receiver;
    Account account;
    ListAppender<ILoggingEvent> workerLog;

    @BeforeEach
    void setUp() throws Exception {
        data.clearDeliveries();
        receiver = new TestReceiver();
        account = data.newAccount();
        workerLog = new ListAppender<>();
        workerLog.start();
        ((Logger) LoggerFactory.getLogger(DeliveryWorker.class)).addAppender(workerLog);
        if (instance == null) {
            instance = startWorkerInstanceBehindProxy();
        }
        proxy.restore();
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(DeliveryWorker.class)).detachAppender(workerLog);
        proxy.restore();
        receiver.close();
        data.clearDeliveries();
    }

    @AfterAll
    static void stopInstance() throws IOException {
        if (instance != null) {
            instance.close();
            instance = null;
        }
        if (proxy != null) {
            proxy.close();
        }
    }

    static ConfigurableApplicationContext startWorkerInstanceBehindProxy() {
        return new SpringApplicationBuilder(WebhookPlatformApplication.class).profiles("test")
                .initializers(context -> {
                    new PostgresInitializer().initialize(context);
                    String url = context.getEnvironment().getProperty("spring.datasource.url");
                    URI direct = URI.create(url.substring("jdbc:".length()));
                    try {
                        proxy = new TcpProxy(direct.getHost(), direct.getPort());
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                    TestPropertyValues.of("spring.datasource.url="
                            + url.replace(direct.getHost() + ":" + direct.getPort(), "127.0.0.1:" + proxy.port()))
                            .applyTo(context.getEnvironment());
                })
                .run("--server.port=0", "--webhooks.worker.enabled=true", "--webhooks.worker.poll-interval=50ms",
                        // This instance's reaper runs once at startup only; the test reaps explicitly.
                        "--webhooks.worker.reaper-interval=1h",
                        "--spring.datasource.hikari.maximum-pool-size=3", "--spring.datasource.hikari.minimum-idle=1",
                        "--spring.datasource.hikari.connection-timeout=1000",
                        "--spring.datasource.hikari.validation-timeout=500");
    }

    String endpoint(String name) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/endpoints").with(account.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"url\":\"" + receiver.url("/hook") + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("endpoint").get("id").asString();
    }

    UUID submit(String endpointId, String key) throws Exception {
        String body = mvc.perform(post("/api/v1/events").header("Authorization", account.bearer())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"order.created\",\"payload\":{},\"endpointIds\":[\"" + endpointId + "\"]}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("deliveries").get(0).get("id").asString());
    }

    Map<String, Object> delivery(UUID id) {
        return jdbc.sql("SELECT status, attempt_count FROM deliveries WHERE id = :id").param("id", id).query().singleRow();
    }

    List<String> attemptStatuses(UUID id) {
        return jdbc.sql("SELECT status FROM delivery_attempts WHERE delivery_id = :id ORDER BY attempt_number")
                .param("id", id).query(String.class).list();
    }

    boolean workerLoggedError(String fragment) {
        return workerLog.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains(fragment));
    }

    /** The surviving instance's reaper recovers the expired lease and the retry is made due now. */
    void recoverAsAHealthyInstance(UUID id) {
        jdbc.sql("UPDATE deliveries SET lease_expires_at = now() - interval '1 second' WHERE id = :id").param("id", id).update();
        assertThat(reaper.reapExpired()).isEqualTo(1);
        jdbc.sql("UPDATE deliveries SET next_attempt_at = now() WHERE id = :id AND status = 'RETRYING'").param("id", id).update();
    }

    /**
     * Outage while the worker polls. Expected: claims fail and are logged, the poll loop keeps going,
     * nothing is claimed or attempted during the outage, and the delivery is sent once the database
     * is back.
     */
    @Test
    void outageDuringClaimIsSurvivedAndTheDeliveryIsSentAfterRecovery() throws Exception {
        String endpointId = endpoint("claim-outage");
        proxy.cut();
        int refusedBefore = proxy.refused();
        UUID id = submit(endpointId, "claim-outage");

        await().atMost(Duration.ofSeconds(15)).until(() -> proxy.refused() >= refusedBefore + 3
                && workerLog.list.stream().anyMatch(e -> e.getFormattedMessage().contains("Delivery poll failed")));
        assertThat(delivery(id)).containsEntry("status", "PENDING").containsEntry("attempt_count", 0);
        assertThat(receiver.requests()).isEmpty();
        assertThat(instance.getBean(DeliveryWorker.class).isRunning()).isTrue();

        proxy.restore();
        await().atMost(Duration.ofSeconds(20)).until(() -> "DELIVERED".equals(delivery(id).get("status")));
        assertThat(receiver.requests()).hasSize(1);
        assertThat(attemptStatuses(id)).containsExactly("SUCCEEDED");
    }

    /**
     * Outage after the receiver accepted (2xx) but before the success is persisted. Expected: the
     * delivery is NOT marked delivered (nothing was recorded), it stays IN_FLIGHT with a STARTED
     * attempt, the lease reaper recovers it, and it is sent again (at-least-once: the receiver sees
     * the same webhook-id twice, as documented in PLAN §7.2).
     */
    @Test
    void outageAfterTheReceiverAcceptedButBeforeSuccessIsPersisted() throws Exception {
        String endpointId = endpoint("completion-outage");
        AtomicInteger calls = new AtomicInteger();
        receiver.respondWith(cutOnFirstCall(calls, 200));
        UUID id = submit(endpointId, "completion-outage");

        await().atMost(Duration.ofSeconds(20)).until(() -> workerLoggedError("failed unexpectedly"));
        proxy.restore();
        assertThat(delivery(id)).containsEntry("status", "IN_FLIGHT").containsEntry("attempt_count", 1);
        assertThat(attemptStatuses(id)).containsExactly("STARTED");

        recoverAsAHealthyInstance(id);
        await().atMost(Duration.ofSeconds(20)).until(() -> "DELIVERED".equals(delivery(id).get("status")));
        assertThat(attemptStatuses(id)).containsExactly("ABANDONED", "SUCCEEDED");
        assertThat(delivery(id)).containsEntry("attempt_count", 2);
        assertThat(receiver.requests()).hasSize(2);
        assertThat(receiver.requests()).extracting(r -> r.header("webhook-id")).containsOnly(receiver.requests().getFirst().header("webhook-id"));
        assertThat(receiver.requests()).extracting(r -> r.header("X-Webhook-Attempt")).containsExactly("1", "2");
    }

    /**
     * Outage while a retryable failure (503) is being scheduled. Expected: the scheduling is lost
     * with the transaction, the lease reaper schedules the retry instead (attempt counted, counter
     * never reset, no duplicate attempt rows), and the retry is delivered.
     */
    @Test
    void outageWhileSchedulingARetryIsRecoveredThroughTheLease() throws Exception {
        String endpointId = endpoint("retry-outage");
        AtomicInteger calls = new AtomicInteger();
        receiver.respondWith(cutOnFirstCall(calls, 503));
        UUID id = submit(endpointId, "retry-outage");

        await().atMost(Duration.ofSeconds(20)).until(() -> workerLoggedError("failed unexpectedly"));
        proxy.restore();
        assertThat(delivery(id)).containsEntry("status", "IN_FLIGHT");

        recoverAsAHealthyInstance(id);
        await().atMost(Duration.ofSeconds(20)).until(() -> "DELIVERED".equals(delivery(id).get("status")));
        assertThat(attemptStatuses(id)).containsExactly("ABANDONED", "SUCCEEDED");
        assertThat(jdbc.sql("SELECT count(DISTINCT attempt_number) = count(*) FROM delivery_attempts WHERE delivery_id = :id")
                .param("id", id).query(Boolean.class).single()).isTrue();
        assertThat(delivery(id)).containsEntry("attempt_count", 2);
    }

    /** First request: cut the database, then answer {@code status}. Later requests: 200. */
    Function<TestReceiver.Request, Response> cutOnFirstCall(AtomicInteger calls, int status) {
        return r -> {
            if (calls.incrementAndGet() == 1) {
                proxy.cut();
                return Response.status(status);
            }
            return Response.status(200);
        };
    }

    /** Forwards TCP connections to PostgreSQL; {@link #cut()} drops them all and refuses new ones. */
    static final class TcpProxy implements AutoCloseable {
        private final ServerSocket server;
        private final String targetHost;
        private final int targetPort;
        private final Set<Socket> open = ConcurrentHashMap.newKeySet();
        private final AtomicInteger refused = new AtomicInteger();
        private volatile boolean up = true;

        TcpProxy(String targetHost, int targetPort) throws IOException {
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread.ofVirtual().start(this::acceptLoop);
        }

        int port() {
            return server.getLocalPort();
        }

        int refused() {
            return refused.get();
        }

        void cut() {
            up = false;
            for (Socket s : open) {
                closeQuietly(s);
            }
            open.clear();
        }

        void restore() {
            up = true;
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                Socket client;
                try {
                    client = server.accept();
                } catch (IOException e) {
                    return;
                }
                if (!up) {
                    refused.incrementAndGet();
                    closeQuietly(client);
                    continue;
                }
                try {
                    Socket upstream = new Socket(targetHost, targetPort);
                    open.add(client);
                    open.add(upstream);
                    Thread.ofVirtual().start(() -> pump(client, upstream));
                    Thread.ofVirtual().start(() -> pump(upstream, client));
                } catch (IOException e) {
                    closeQuietly(client);
                }
            }
        }

        private void pump(Socket from, Socket to) {
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, n);
                    out.flush();
                }
            } catch (IOException e) {
                // connection cut or closed
            } finally {
                closeQuietly(from);
                closeQuietly(to);
                open.remove(from);
                open.remove(to);
            }
        }

        private static void closeQuietly(Socket s) {
            try {
                s.close();
            } catch (IOException ignored) {
                // already closed
            }
        }

        @Override
        public void close() throws IOException {
            cut();
            server.close();
        }
    }
}
