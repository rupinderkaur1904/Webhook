package dev.webhooks.platform.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.webhooks.platform.signing.WebhookSigner;
import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import dev.webhooks.platform.support.TestReceiver;
import dev.webhooks.platform.worker.DeliveryProcessor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Audit (C6) contract test: the real delivery path (ingestion, {@link DeliveryProcessor},
 * {@code WebhookHttpClient}) against the receiver simulator's real verification code. The
 * simulator's {@code SignatureVerifier.java} has no dependencies, so it is compiled from its source
 * file at test time and called reflectively: no copy that could drift.
 */
@IntegrationTest
class AuditSigningContractTest {

    static final Path SIMULATOR_VERIFIER =
            Path.of("../receiver-simulator/src/main/java/dev/webhooks/simulator/SignatureVerifier.java");
    static final Path VECTORS = Path.of("../examples/receivers/test-vectors.json");

    static Method simulatorVerify;

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired DeliveryRepository deliveries;
    @Autowired DeliveryProcessor processor;

    TestReceiver receiver;
    Account account;
    String endpointId;
    String secret;

    @BeforeAll
    static void compileSimulatorVerifier() throws Exception {
        Path out = Files.createTempDirectory("sim-verifier");
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", out.toString(),
                SIMULATOR_VERIFIER.toString());
        assertThat(rc).as("compiling the simulator's SignatureVerifier").isZero();
        ClassLoader loader = new URLClassLoader(new URL[] {out.toUri().toURL()}, null);
        Class<?> verifier = loader.loadClass("dev.webhooks.simulator.SignatureVerifier");
        simulatorVerify = verifier.getMethod("verify", String.class, String.class, String.class, String.class,
                byte[].class, Instant.class, Duration.class);
    }

    /** The simulator's verdict: VALID, INVALID_SIGNATURE, TIMESTAMP_OUT_OF_TOLERANCE or MISSING_HEADERS. */
    static String simulator(String secret, String id, String timestamp, String signature, byte[] body, Instant now)
            throws Exception {
        return simulatorVerify.invoke(null, secret, id, timestamp, signature, body, now, Duration.ofMinutes(5)).toString();
    }

    static String simulator(String secret, TestReceiver.Request r) throws Exception {
        return simulator(secret, r.header("webhook-id"), r.header("webhook-timestamp"), r.header("webhook-signature"),
                r.body(), Instant.now());
    }

    @BeforeEach
    void setUp() throws Exception {
        data.clearDeliveries();
        receiver = new TestReceiver();
        account = data.newAccount();
        JsonNode created = json.readTree(mvc.perform(post("/api/v1/endpoints").with(account.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"contract\",\"url\":\"" + receiver.url("/hook") + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        endpointId = created.get("endpoint").get("id").asString();
        secret = created.get("secret").asString();
    }

    @AfterEach
    void tearDown() {
        receiver.close();
    }

    /** Submits {@code payloadJson} (raw text, as a producer would send it) and delivers it once. */
    UUID deliver(String payloadJson) throws Exception {
        String accepted = mvc.perform(post("/api/v1/events").header("Authorization", account.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"eventType\":\"order.created\",\"payload\":" + payloadJson + "}").getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID deliveryId = UUID.fromString(json.readTree(accepted).get("deliveries").get(0).get("id").asString());
        List<ClaimedDelivery> claimed = deliveries.claimDue(10, "contract", Duration.ofSeconds(60));
        assertThat(claimed).hasSize(1);
        processor.process(claimed.getFirst());
        return deliveryId;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"orderId\":\"1001\"}",
            "{\"name\":\"Zoë Šimić 東京 😀 \\u00e9\"}",                       // non-ASCII, raw and escaped
            "{\"emoji\":\"\\ud83d\\ude00\",\"combining\":\"e\\u0301\"}",      // surrogate pair, combining mark
            "{\"quote\":\"he said \\\"hi\\\"\",\"backslash\":\"C:\\\\temp\\\\x\",\"slash\":\"a\\/b\"}",
            "{\"text\":\"line1\\nline2\\r\\n\\ttabbed\",\"ctrl\":\"\\u0000\\u0001\\u001f\"}",
            "{\"separators\":\"\\u2028\\u2029\",\"bom\":\"\\ufeff\",\"nbsp\":\"\\u00a0\"}",
            "{ \"spaced\" :  \"  leading and trailing  \" ,\n  \"nested\" : { \"a\" : [ 1 , 2 , 3 ] } }",
            "{\"html\":\"<script>alert('x')</script>&amp;\"}",
            "{\"numbers\":[0,-1,9007199254740993,123456789012345678901234567890,1.5,-0.0,1e3],\"bool\":true,\"nil\":null}",
            "{\"deep\":{\"a\":{\"b\":{\"c\":{\"d\":[{\"e\":\"f\"}]}}}},\"empty\":{},\"list\":[]}"})
    void bodySentIsByteIdenticalToTheBytesSignedAndStored(String payload) throws Exception {
        UUID deliveryId = deliver(payload);

        assertThat(receiver.requests()).hasSize(1);
        TestReceiver.Request request = receiver.requests().getFirst();
        byte[] sent = request.body();

        // 1. The simulator's own verifier accepts exactly the bytes on the wire.
        assertThat(simulator(secret, request)).isEqualTo("VALID");
        // 2. The platform's signer over id.timestamp.body reproduces the header exactly.
        assertThat(request.header("webhook-signature")).isEqualTo(WebhookSigner.sign(secret, request.header("webhook-id"),
                Long.parseLong(request.header("webhook-timestamp")), sent));
        // 3. The bytes sent are the stored event body, byte for byte.
        JsonNode detail = json.readTree(mvc.perform(get("/api/v1/deliveries/" + deliveryId).header("Authorization", account.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String eventId = detail.get("delivery").get("eventId").asString();
        String stored = jdbc.sql("SELECT body FROM events WHERE id = :id").param("id", UUID.fromString(eventId))
                .query(String.class).single();
        assertThat(sent).isEqualTo(stored.getBytes(StandardCharsets.UTF_8));
        assertThat(request.header("webhook-id")).isEqualTo(eventId);
        // 4. What the dashboard shows is the same document, and its data is what the producer submitted.
        JsonNode wire = json.readTree(sent);
        assertThat(detail.get("eventBody")).isEqualTo(wire);
        assertThat(json.writeValueAsString(detail.get("eventBody"))).isEqualTo(stored);
        assertThat(wire.get("data")).isEqualTo(json.readTree(payload));
        // 5. The signature covers id, timestamp and body: any change is rejected.
        byte[] tampered = Arrays.copyOf(sent, sent.length);
        tampered[tampered.length - 2] ^= 0x01;
        String id = request.header("webhook-id");
        String ts = request.header("webhook-timestamp");
        String sig = request.header("webhook-signature");
        assertThat(simulator(secret, id, ts, sig, tampered, Instant.now())).isEqualTo("INVALID_SIGNATURE");
        assertThat(simulator(secret, id, ts, sig, (new String(sent, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8), Instant.now()))
                .isEqualTo("INVALID_SIGNATURE");
        assertThat(simulator(secret, id, Long.toString(Long.parseLong(ts) + 1), sig, sent, Instant.now())).isEqualTo("INVALID_SIGNATURE");
        assertThat(simulator(secret, UUID.randomUUID().toString(), ts, sig, sent, Instant.now())).isEqualTo("INVALID_SIGNATURE");
        assertThat(simulator("whsec_" + java.util.Base64.getEncoder().encodeToString(new byte[32]), id, ts, sig, sent, Instant.now()))
                .isEqualTo("INVALID_SIGNATURE");
        assertThat(simulator(secret, id, ts, sig, sent, Instant.now().plus(Duration.ofMinutes(6))))
                .isEqualTo("TIMESTAMP_OUT_OF_TOLERANCE");
    }

    /**
     * A lone UTF-16 surrogate is valid JSON escape syntax but not encodable as UTF-8. It must not end
     * up as different bytes signed vs sent vs stored: whatever the platform does, the three agree.
     */
    @Test
    void loneSurrogateStillProducesConsistentBytes() throws Exception {
        UUID deliveryId = deliver("{\"broken\":\"\\ud800x\"}");
        TestReceiver.Request request = receiver.requests().getFirst();
        assertThat(simulator(secret, request)).isEqualTo("VALID");
        String eventId = json.readTree(mvc.perform(get("/api/v1/deliveries/" + deliveryId).header("Authorization", account.bearer()))
                .andReturn().getResponse().getContentAsString()).get("delivery").get("eventId").asString();
        String stored = jdbc.sql("SELECT body FROM events WHERE id = :id").param("id", UUID.fromString(eventId))
                .query(String.class).single();
        assertThat(request.body()).isEqualTo(stored.getBytes(StandardCharsets.UTF_8));
        // Record what the receiver actually got for the audit report.
        System.out.println("AUDIT lone surrogate delivered as: " + json.readTree(request.body()).get("data"));
    }

    @Test
    void rotationSendsTwoSignaturesEachAcceptedByTheSimulatorWithItsOwnSecret() throws Exception {
        String rotated = json.readTree(mvc.perform(post("/api/v1/endpoints/" + endpointId + "/rotate-secret")
                        .with(account.session()).contentType(MediaType.APPLICATION_JSON).content("{\"gracePeriodHours\":1}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("secret").asString();
        deliver("{\"rotation\":\"ünïcødé\"}");

        TestReceiver.Request request = receiver.requests().getFirst();
        String header = request.header("webhook-signature");
        assertThat(header.split(" ")).hasSize(2).allSatisfy(part -> assertThat(part).startsWith("v1,"));
        assertThat(simulator(rotated, request)).isEqualTo("VALID");
        assertThat(simulator(secret, request)).isEqualTo("VALID");
        // Each signature on its own belongs to exactly one secret, new one first.
        String[] parts = header.split(" ");
        String id = request.header("webhook-id");
        String ts = request.header("webhook-timestamp");
        assertThat(simulator(rotated, id, ts, parts[0], request.body(), Instant.now())).isEqualTo("VALID");
        assertThat(simulator(secret, id, ts, parts[0], request.body(), Instant.now())).isEqualTo("INVALID_SIGNATURE");
        assertThat(simulator(secret, id, ts, parts[1], request.body(), Instant.now())).isEqualTo("VALID");
        assertThat(simulator(rotated, id, ts, parts[1], request.body(), Instant.now())).isEqualTo("INVALID_SIGNATURE");
    }

    @Test
    void testEventsUseTheCurrentUrlSecretAndEnvelope() throws Exception {
        TestReceiver moved = new TestReceiver();
        try {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/endpoints/" + endpointId)
                            .with(account.session()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"" + moved.url("/moved") + "\"}"))
                    .andExpect(status().isOk());
            String rotated = json.readTree(mvc.perform(post("/api/v1/endpoints/" + endpointId + "/rotate-secret")
                            .with(account.session()).contentType(MediaType.APPLICATION_JSON).content("{\"gracePeriodHours\":0}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("secret").asString();
            mvc.perform(post("/api/v1/endpoints/" + endpointId + "/test").with(account.session())).andExpect(status().isAccepted());
            List<ClaimedDelivery> claimed = deliveries.claimDue(10, "contract", Duration.ofSeconds(60));
            assertThat(claimed).hasSize(1);
            processor.process(claimed.getFirst());

            assertThat(receiver.requests()).isEmpty();
            assertThat(moved.requests()).hasSize(1);
            TestReceiver.Request request = moved.requests().getFirst();
            assertThat(request.path()).isEqualTo("/moved");
            assertThat(request.header("webhook-signature").split(" ")).hasSize(1);
            assertThat(simulator(rotated, request)).isEqualTo("VALID");
            assertThat(simulator(secret, request)).isEqualTo("INVALID_SIGNATURE");
            JsonNode body = json.readTree(request.body());
            assertThat(body.get("type").asString()).isEqualTo("webhook.test");
            assertThat(body.get("id").asString()).isEqualTo(request.header("webhook-id"));
            assertThat(body.get("data").get("endpointId").asString()).isEqualTo(endpointId);
            assertThat(request.header("Content-Type")).startsWith("application/json");
        } finally {
            moved.close();
        }
    }

    /** The shared vectors (generated by the platform signer) agree with both the signer and the simulator. */
    @Test
    void sharedTestVectorsAgreeWithTheSignerAndTheSimulator() throws Exception {
        JsonNode vectors = json.readTree(Files.readString(VECTORS, StandardCharsets.UTF_8)).get("vectors");
        assertThat(vectors.size()).isGreaterThan(10);
        List<String> mismatches = new ArrayList<>();
        for (JsonNode v : vectors) {
            JsonNode h = v.get("headers");
            String id = h.has("webhook-id") ? h.get("webhook-id").asString() : null;
            String ts = h.has("webhook-timestamp") ? h.get("webhook-timestamp").asString() : null;
            String sig = h.has("webhook-signature") ? h.get("webhook-signature").asString() : null;
            byte[] body = v.get("body").asString().getBytes(StandardCharsets.UTF_8);
            Instant now = Instant.ofEpochSecond(v.get("now").asLong());
            String verdict = simulator(v.get("secret").asString(), id, ts, sig, body, now);
            String expectedReason = v.get("expected").get("reason").asString();
            String expectedVerdict = switch (expectedReason) {
                case "ok" -> "VALID";
                case "no_matching_signature" -> "INVALID_SIGNATURE";
                case "timestamp_out_of_tolerance" -> "TIMESTAMP_OUT_OF_TOLERANCE";
                case "missing_header", "invalid_timestamp" -> "MISSING_HEADERS";
                default -> "?" + expectedReason;
            };
            if (!verdict.equals(expectedVerdict)) {
                mismatches.add(v.get("name").asString() + ": simulator " + verdict + ", vectors " + expectedReason);
            }
            if (expectedReason.equals("ok")) {
                String mine = WebhookSigner.sign(v.get("secret").asString(), id, Long.parseLong(ts), body);
                if (!Arrays.asList(sig.split(" ")).contains(mine)) {
                    mismatches.add(v.get("name").asString() + ": platform signer produced none of the vector's signatures");
                }
            }
        }
        assertThat(mismatches).isEmpty();
    }
}
