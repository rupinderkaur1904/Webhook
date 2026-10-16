package dev.webhooks.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.webhooks.platform.crypto.SecretCipher;
import dev.webhooks.platform.endpoint.EndpointRepository;
import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.TestData;
import dev.webhooks.platform.support.TestData.Account;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Audit (C5): endpoint create / read / PATCH / enable / disable / delete, secret rotation and
 * secret exposure, against real PostgreSQL.
 */
@IntegrationTest
class AuditEndpointLifecycleTest {

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired JdbcClient jdbc;
    @Autowired SecretCipher cipher;
    @Autowired ObjectMapper json;
    @Autowired EndpointRepository endpoints;
    @Autowired TransactionTemplate tx;
    @Autowired DataSource dataSource;

    Account account;

    @BeforeEach
    void setUp() {
        account = data.newAccount();
    }

    JsonNode create(String body) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/endpoints").with(account.session())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    String patchBody(String id, String body, int expectedStatus) throws Exception {
        return mvc.perform(patch("/api/v1/endpoints/" + id).with(account.session())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
    }

    JsonNode read(String id) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/endpoints/" + id).header("Authorization", account.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** The stored, user-editable columns of an endpoint row, read straight from the database. */
    Map<String, Object> row(String id) {
        return jdbc.sql("""
                        SELECT name, description, url, array_to_string(event_types, ',') AS types, enabled,
                               secret_prefix, updated_at
                        FROM endpoints WHERE id = :id
                        """)
                .param("id", UUID.fromString(id)).query().singleRow();
    }

    @Test
    void partialPatchChangesOnlyTheGivenFieldsAndKeepsIdAndHistory() throws Exception {
        JsonNode created = create("""
                {"name":"orders","description":"first","url":"http://127.0.0.1:9/a","eventTypes":["order.*"]}""");
        String id = created.get("endpoint").get("id").asString();
        String delivery = json.readTree(mvc.perform(post("/api/v1/endpoints/" + id + "/test").with(account.session()))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString())
                .get("deliveries").get(0).get("id").asString();

        JsonNode afterName = json.readTree(patchBody(id, "{\"name\":\"  renamed  \"}", 200));
        assertThat(afterName.get("id").asString()).isEqualTo(id);
        assertThat(afterName.get("name").asString()).isEqualTo("renamed");
        assertThat(afterName.get("description").asString()).isEqualTo("first");
        assertThat(afterName.get("url").asString()).isEqualTo("http://127.0.0.1:9/a");
        assertThat(afterName.get("eventTypes").toString()).isEqualTo("[\"order.*\"]");
        assertThat(afterName.get("enabled").asBoolean()).isTrue();
        assertThat(afterName.get("secretPrefix").asString()).isEqualTo(created.get("endpoint").get("secretPrefix").asString());
        assertThat(afterName.has("secret")).isFalse();

        JsonNode afterUrl = json.readTree(patchBody(id, "{\"url\":\"http://127.0.0.1:9/b\"}", 200));
        assertThat(afterUrl.get("name").asString()).isEqualTo("renamed");
        assertThat(afterUrl.get("url").asString()).isEqualTo("http://127.0.0.1:9/b");

        // Delivery history survives edits and still belongs to the same endpoint id.
        mvc.perform(get("/api/v1/deliveries/" + delivery).header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.endpointId").value(id));

        // Disable and re-enable keep everything else.
        assertThat(json.readTree(patchBody(id, "{\"enabled\":false}", 200)).get("enabled").asBoolean()).isFalse();
        JsonNode reenabled = json.readTree(patchBody(id, "{\"enabled\":true}", 200));
        assertThat(reenabled.get("enabled").asBoolean()).isTrue();
        assertThat(reenabled.get("url").asString()).isEqualTo("http://127.0.0.1:9/b");
        assertThat(reenabled.get("description").asString()).isEqualTo("first");
    }

    @Test
    void descriptionOmittedOrNullIsUnchangedAndEmptyClearsIt() throws Exception {
        String id = create("{\"name\":\"d\",\"description\":\"keep me\",\"url\":\"http://127.0.0.1:9/\"}")
                .get("endpoint").get("id").asString();

        assertThat(json.readTree(patchBody(id, "{\"name\":\"d2\"}", 200)).get("description").asString())
                .as("omitted description is left unchanged").isEqualTo("keep me");
        // JSON null and omission cannot be told apart by the request record: null also means "unchanged".
        assertThat(json.readTree(patchBody(id, "{\"description\":null}", 200)).get("description").asString())
                .as("explicit null is treated as 'unchanged'").isEqualTo("keep me");
        assertThat(json.readTree(patchBody(id, "{\"description\":\"  padded  \"}", 200)).get("description").asString())
                .isEqualTo("padded");
        assertThat(json.readTree(patchBody(id, "{\"description\":\"   \"}", 200)).get("description").isNull())
                .as("blank clears the description").isTrue();
        assertThat(row(id).get("description")).isNull();
        patchBody(id, "{\"description\":\"again\"}", 200);
        assertThat(json.readTree(patchBody(id, "{\"description\":\"\"}", 200)).get("description").isNull()).isTrue();
    }

    /**
     * Audit finding: PATCH validated {@code name} with {@code @Size(min = 1)} but stored it trimmed, so a
     * whitespace-only name passed validation, hit the database CHECK and surfaced as a 500.
     */
    @ParameterizedTest
    @ValueSource(strings = {" ", "   ", "\\t"})
    void whitespaceOnlyNameIsAValidationErrorNotAServerError(String name) throws Exception {
        String id = create("{\"name\":\"n\",\"url\":\"http://127.0.0.1:9/\"}").get("endpoint").get("id").asString();
        mvc.perform(patch("/api/v1/endpoints/" + id).with(account.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(row(id).get("name")).isEqualTo("n");
    }

    @Test
    void invalidUpdatesFailAtomicallyWithNoPartialWrite() throws Exception {
        String id = create("""
                {"name":"atomic","description":"orig","url":"http://127.0.0.1:9/a","eventTypes":["order.created"]}""")
                .get("endpoint").get("id").asString();
        create("{\"name\":\"taken\",\"url\":\"http://127.0.0.1:9/t\"}");
        Map<String, Object> before = row(id);

        // A bad URL alongside valid changes: nothing is written.
        assertThat(json.readTree(patchBody(id, """
                {"name":"atomic-2","description":"changed","url":"https://10.0.0.1/x","enabled":false}""", 422))
                .get("code").asString()).isEqualTo("URL_NOT_ALLOWED");
        assertThat(row(id)).isEqualTo(before);

        // A duplicate name alongside valid changes, including a valid new URL.
        assertThat(json.readTree(patchBody(id, """
                {"name":"taken","description":"changed","url":"http://127.0.0.1:9/new","eventTypes":["x.y"]}""", 409))
                .get("code").asString()).isEqualTo("ENDPOINT_NAME_TAKEN");
        assertThat(row(id)).isEqualTo(before);

        // Too-long fields and bad event types are rejected by validation before anything runs.
        patchBody(id, "{\"name\":\"ok\",\"description\":\"" + "x".repeat(501) + "\"}", 422);
        patchBody(id, "{\"name\":\"" + "n".repeat(101) + "\"}", 422);
        patchBody(id, "{\"url\":\"http://127.0.0.1:9/" + "u".repeat(2048) + "\"}", 422);
        patchBody(id, "{\"name\":\"ok\",\"eventTypes\":[\"Order Created\"]}", 422);
        patchBody(id, "{\"name\":\"ok\",\"eventTypes\":[null]}", 422);
        patchBody(id, "{\"url\":\"\"}", 422);
        assertThat(row(id)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://localhost/x", "https://127.0.0.1.nip.invalid/x", "https://[::1]/x", "https://169.254.169.254/",
            "https://[fd00:ec2::254]/", "https://0x7f.1/", "https://2130706433/", "https://127.1/", "https://0/",
            "https://[::ffff:10.0.0.1]/", "http://example.com/", "https://user@example.com/", "https://example.com/#f",
            "ftp://example.com/"})
    void urlChangesRunTheSameValidationAsCreation(String url) throws Exception {
        String id = create("{\"name\":\"ssrf\",\"url\":\"http://127.0.0.1:9/a\"}").get("endpoint").get("id").asString();
        mvc.perform(post("/api/v1/endpoints").with(account.session()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ssrf-create\",\"url\":\"" + url + "\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("URL_NOT_ALLOWED"));
        mvc.perform(patch("/api/v1/endpoints/" + id).with(account.session()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("URL_NOT_ALLOWED"));
        assertThat(row(id).get("url")).isEqualTo("http://127.0.0.1:9/a");
    }

    @Test
    void eventTypeChangesApplyToTheNextIngestion() throws Exception {
        String id = create("{\"name\":\"types\",\"url\":\"http://127.0.0.1:9/\",\"eventTypes\":[\"order.*\"]}")
                .get("endpoint").get("id").asString();
        assertThat(targets("order.created")).containsExactly(id);
        assertThat(targets("payment.settled")).isEmpty();

        patchBody(id, "{\"eventTypes\":[\"payment.*\"]}", 200);
        assertThat(targets("order.created")).isEmpty();
        assertThat(targets("payment.settled")).containsExactly(id);

        patchBody(id, "{\"eventTypes\":[]}", 200);
        assertThat(targets("anything.at.all")).as("empty filter receives every event").containsExactly(id);

        patchBody(id, "{\"enabled\":false}", 200);
        assertThat(targets("anything.at.all")).isEmpty();
    }

    List<String> targets(String eventType) throws Exception {
        JsonNode accepted = json.readTree(mvc.perform(post("/api/v1/events").header("Authorization", account.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"" + eventType + "\",\"payload\":{}}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        List<String> ids = new ArrayList<>();
        accepted.get("deliveries").forEach(d -> ids.add(d.get("endpointId").asString()));
        return ids;
    }

    // ---- Concurrency ------------------------------------------------------------------------------------

    /**
     * Audit finding: two PATCHes that change different fields must both take effect. The service read
     * the row, merged the request in Java and wrote every column back, without locking the row, so the
     * second writer silently restored the first writer's field (lost update).
     *
     * <p>Deterministic interleaving: an outside transaction holds the row lock while both PATCHes start;
     * they are released together once both are waiting on it.
     */
    @Test
    void concurrentPatchesOfDifferentFieldsDoNotLoseUpdates() throws Exception {
        String id = create("{\"name\":\"race\",\"description\":\"d0\",\"url\":\"http://127.0.0.1:9/a\"}")
                .get("endpoint").get("id").asString();

        List<Integer> statuses = whileRowLocked(id, List.of(
                () -> mvc.perform(patch("/api/v1/endpoints/" + id).with(account.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"race-renamed\"}"))
                        .andReturn().getResponse().getStatus(),
                () -> mvc.perform(patch("/api/v1/endpoints/" + id).with(account.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"http://127.0.0.1:9/moved\"}"))
                        .andReturn().getResponse().getStatus()));

        assertThat(statuses).containsOnly(200);
        Map<String, Object> after = row(id);
        assertThat(after.get("name")).isEqualTo("race-renamed");
        assertThat(after.get("url")).isEqualTo("http://127.0.0.1:9/moved");
        assertThat(after.get("description")).isEqualTo("d0");
    }

    @Test
    void patchConcurrentWithRotationKeepsBothChanges() throws Exception {
        JsonNode created = create("{\"name\":\"rot-race\",\"url\":\"http://127.0.0.1:9/a\"}");
        String id = created.get("endpoint").get("id").asString();

        List<String> rotated = new ArrayList<>();
        List<Integer> statuses = whileRowLocked(id, List.of(
                () -> mvc.perform(patch("/api/v1/endpoints/" + id).with(account.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"edited\"}"))
                        .andReturn().getResponse().getStatus(),
                () -> {
                    var response = mvc.perform(post("/api/v1/endpoints/" + id + "/rotate-secret").with(account.session())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"gracePeriodHours\":1}")).andReturn().getResponse();
                    rotated.add(json.readTree(response.getContentAsString()).get("secret").asString());
                    return response.getStatus();
                }));

        assertThat(statuses).containsOnly(200);
        Map<String, Object> after = row(id);
        assertThat(after.get("description")).isEqualTo("edited");
        assertThat(after.get("secret_prefix")).isEqualTo(rotated.getFirst().substring(0, 12));
        var secrets = endpoints.findSigningSecrets(UUID.fromString(id)).orElseThrow();
        assertThat(cipher.decrypt(secrets.current(), UUID.fromString(id))).isEqualTo(rotated.getFirst());
        assertThat(cipher.decrypt(secrets.previous(), UUID.fromString(id))).isEqualTo(created.get("secret").asString());
    }

    interface Call {
        int run() throws Exception;
    }

    /**
     * Runs {@code calls} concurrently while another connection holds {@code FOR UPDATE} on the endpoint
     * row, waits (bounded) until every call is blocked on that lock, then releases it.
     */
    List<Integer> whileRowLocked(String id, List<Call> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try (Connection lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (var st = lock.prepareStatement("SELECT 1 FROM endpoints WHERE id = ? FOR UPDATE")) {
                st.setObject(1, UUID.fromString(id));
                st.executeQuery().close();
            }
            int lockPid;
            try (var st = lock.prepareStatement("SELECT pg_backend_pid()"); var rs = st.executeQuery()) {
                rs.next();
                lockPid = rs.getInt(1);
            }
            List<Future<Integer>> futures = new ArrayList<>();
            for (Call call : calls) {
                futures.add(pool.submit(call::run));
            }
            Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
            while (blockedBy(lockPid) < calls.size()) {
                if (futures.stream().allMatch(Future::isDone)) {
                    List<Object> done = new ArrayList<>();
                    for (Future<Integer> f : futures) {
                        try {
                            done.add(f.get());
                        } catch (Exception e) {
                            done.add(e);
                        }
                    }
                    throw new AssertionError("calls finished without blocking on the row lock: " + done);
                }
                assertThat(Instant.now()).as("both calls should block on the row lock").isBefore(deadline);
                Thread.onSpinWait();
            }
            lock.rollback();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(10, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Backends waiting, directly or behind another waiter, on the lock held by {@code pid}. The second
     * waiter for a row queues behind the first (tuple lock), so it is blocked by that waiter, not by pid.
     */
    int blockedBy(int pid) {
        return jdbc.sql("""
                        WITH RECURSIVE waiting(pid) AS (
                            SELECT a.pid FROM pg_stat_activity a WHERE :pid = ANY(pg_blocking_pids(a.pid))
                            UNION
                            SELECT a.pid FROM pg_stat_activity a JOIN waiting w ON w.pid = ANY(pg_blocking_pids(a.pid))
                        )
                        SELECT count(*) FROM waiting
                        """)
                .param("pid", pid).query(Integer.class).single();
    }

    // ---- Secrets -----------------------------------------------------------------------------------------

    @Test
    void repeatedRotationWithinTheGracePeriodRetiresTheOldestSecretImmediately() throws Exception {
        JsonNode created = create("{\"name\":\"rot\",\"url\":\"http://127.0.0.1:9/a\"}");
        UUID id = UUID.fromString(created.get("endpoint").get("id").asString());
        String original = created.get("secret").asString();

        String first = rotate(id, 24).get("secret").asString();
        String second = rotate(id, 24).get("secret").asString();

        var secrets = endpoints.findSigningSecrets(id).orElseThrow();
        assertThat(cipher.decrypt(secrets.current(), id)).isEqualTo(second);
        assertThat(cipher.decrypt(secrets.previous(), id))
                .as("only one previous secret is kept: the second rotation replaces it")
                .isEqualTo(first);
        assertThat(List.of(cipher.decrypt(secrets.current(), id), cipher.decrypt(secrets.previous(), id)))
                .as("the original secret stops signing at the second rotation, before its own grace period ended")
                .doesNotContain(original);

        // A zero grace period retires the remaining previous secret too.
        rotate(id, 0);
        assertThat(endpoints.findSigningSecrets(id).orElseThrow().previous()).isNull();
        assertThat(read(id.toString()).get("previousSecretExpiresAt").isNull()).isTrue();
    }

    @Test
    void previousSecretRetiresExactlyAtItsExpiry() throws Exception {
        JsonNode created = create("{\"name\":\"exp\",\"url\":\"http://127.0.0.1:9/a\"}");
        UUID id = UUID.fromString(created.get("endpoint").get("id").asString());
        JsonNode rotation = rotate(id, 1);
        Instant expires = Instant.parse(rotation.get("previousSecretExpiresAt").asString());
        assertThat(expires).isBetween(Instant.now().plus(Duration.ofMinutes(59)), Instant.now().plus(Duration.ofMinutes(61)));
        assertThat(read(id.toString()).get("previousSecretExpiresAt").asString()).isNotEmpty();

        // Inside one transaction now() is fixed: expiry == now() is already retired, now() + 1µs is not.
        Boolean[] atBoundary = tx.execute(s -> {
            jdbc.sql("UPDATE endpoints SET previous_secret_expires_at = now() + interval '1 microsecond' WHERE id = :id")
                    .param("id", id).update();
            boolean before = endpoints.findSigningSecrets(id).orElseThrow().previous() != null;
            jdbc.sql("UPDATE endpoints SET previous_secret_expires_at = now() WHERE id = :id").param("id", id).update();
            boolean at = endpoints.findSigningSecrets(id).orElseThrow().previous() != null;
            s.setRollbackOnly();
            return new Boolean[] {before, at};
        });
        assertThat(atBoundary).containsExactly(true, false);
    }

    @Test
    void secretsAreEncryptedAndBoundToTheirEndpointAtRest() throws Exception {
        JsonNode created = create("{\"name\":\"enc\",\"url\":\"http://127.0.0.1:9/a\"}");
        UUID id = UUID.fromString(created.get("endpoint").get("id").asString());
        String original = created.get("secret").asString();
        String rotated = rotate(id, 2).get("secret").asString();

        Map<String, Object> stored = jdbc.sql("SELECT secret_ciphertext, previous_secret_ciphertext FROM endpoints WHERE id = :id")
                .param("id", id).query().singleRow();
        for (Object column : List.of(stored.get("secret_ciphertext"), stored.get("previous_secret_ciphertext"))) {
            byte[] bytes = (byte[]) column;
            assertThat(bytes[0]).as("format version").isEqualTo((byte) 1);
            // version(1) | iv(12) | ciphertext (len of "whsec_" + 44 base64 chars) | GCM tag(16)
            assertThat(bytes).hasSize(1 + 12 + original.length() + 16);
            String latin1 = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            assertThat(latin1).doesNotContain("whsec_").doesNotContain(original.substring(6, 20))
                    .doesNotContain(rotated.substring(6, 20));
            UUID otherEndpoint = UUID.randomUUID();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> cipher.decrypt(bytes, otherEndpoint))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(cipher.decrypt((byte[]) stored.get("secret_ciphertext"), id)).isEqualTo(rotated);
        assertThat(cipher.decrypt((byte[]) stored.get("previous_secret_ciphertext"), id)).isEqualTo(original);
    }

    @Test
    void secretsAppearOnlyInCreateAndRotateResponses() throws Exception {
        JsonNode created = create("{\"name\":\"leak\",\"url\":\"http://127.0.0.1:9/a\"}");
        String id = created.get("endpoint").get("id").asString();
        String original = created.get("secret").asString();
        String rotated = rotate(UUID.fromString(id), 24).get("secret").asString();
        String delivery = json.readTree(mvc.perform(post("/api/v1/endpoints/" + id + "/test").with(account.session()))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString())
                .get("deliveries").get(0).get("id").asString();

        List<String> bodies = new ArrayList<>();
        bodies.add(mvc.perform(get("/api/v1/endpoints").with(account.session())).andReturn().getResponse().getContentAsString());
        bodies.add(mvc.perform(get("/api/v1/endpoints/" + id).with(account.session())).andReturn().getResponse().getContentAsString());
        bodies.add(mvc.perform(get("/api/v1/endpoints/" + id + "/health").with(account.session())).andReturn().getResponse().getContentAsString());
        bodies.add(patchBody(id, "{\"description\":\"x\"}", 200));
        bodies.add(mvc.perform(post("/api/v1/endpoints/" + id + "/circuit/reset").with(account.session()))
                .andReturn().getResponse().getContentAsString());
        bodies.add(mvc.perform(get("/api/v1/deliveries/" + delivery).with(account.session())).andReturn().getResponse().getContentAsString());
        bodies.add(mvc.perform(get("/api/v1/deliveries").with(account.session())).andReturn().getResponse().getContentAsString());
        bodies.add(mvc.perform(get("/api/v1/events").with(account.session())).andReturn().getResponse().getContentAsString());
        bodies.add(mvc.perform(get("/api/v1/metrics/overview").with(account.session())).andReturn().getResponse().getContentAsString());
        // Validation and conflict errors echo nothing secret either.
        bodies.add(patchBody(id, "{\"url\":\"https://10.0.0.1/\"}", 422));

        for (String secret : List.of(original, rotated)) {
            String key = secret.substring("whsec_".length());
            assertThat(bodies).allSatisfy(b -> assertThat(b).doesNotContain(key).doesNotContain(key.substring(0, 16)));
        }
        assertThat(bodies.get(1)).contains("\"secretPrefix\":\"" + rotated.substring(0, 12) + "\"");

        mvc.perform(delete("/api/v1/endpoints/" + id).with(account.session())).andExpect(status().isNoContent());
    }

    JsonNode rotate(UUID id, int hours) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/endpoints/" + id + "/rotate-secret").with(account.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gracePeriodHours\":" + hours + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void rotationRejectsOutOfRangeGracePeriodsWithoutChangingTheSecret() throws Exception {
        JsonNode created = create("{\"name\":\"range\",\"url\":\"http://127.0.0.1:9/a\"}");
        String id = created.get("endpoint").get("id").asString();
        for (String body : List.of("{\"gracePeriodHours\":-1}", "{\"gracePeriodHours\":73}", "{\"gracePeriodHours\":\"x\"}")) {
            int code = mvc.perform(post("/api/v1/endpoints/" + id + "/rotate-secret").with(account.session())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
            assertThat(code).as(body).isIn(400, 422);
        }
        assertThat(row(id).get("secret_prefix")).isEqualTo(created.get("secret").asString().substring(0, 12));
        // No body at all means the default 24h grace period.
        JsonNode defaulted = json.readTree(mvc.perform(post("/api/v1/endpoints/" + id + "/rotate-secret").with(account.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(Instant.parse(defaulted.get("previousSecretExpiresAt").asString()))
                .isBetween(Instant.now().plus(Duration.ofHours(23)), Instant.now().plus(Duration.ofHours(25)));
    }
}
