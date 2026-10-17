package dev.webhooks.platform.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.ScratchDatabase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Upgrading a database that was created by an earlier release: migrate a fresh database to V1..V4,
 * write the rows that release would have written, then migrate to the latest version and check that
 * every row survived unchanged and the latest constraints hold on top of the old data.
 */
@IntegrationTest
class AuditMigrationUpgradeTest {

    @Autowired DataSource dataSource;
    @Autowired Environment env;

    @ParameterizedTest(name = "upgrade from V{0}")
    @ValueSource(ints = {1, 2, 3, 4})
    void dataWrittenByAnEarlierVersionSurvivesTheUpgradeToTheLatest(int fromVersion) {
        try (ScratchDatabase db = new ScratchDatabase(dataSource, env, "audit_upgrade_v" + fromVersion)) {
            db.migrateTo(Integer.toString(fromVersion));
            JdbcClient jdbc = db.jdbc();
            Seed seed = seed(jdbc, fromVersion);
            Map<String, Long> countsBefore = counts(jdbc);
            List<String> deliveriesBefore = deliveryFingerprint(jdbc);

            var result = db.flyway().migrate();

            assertThat(result.success).isTrue();
            assertThat(result.targetSchemaVersion).isEqualTo("5");
            assertThat(db.flyway().validateWithResult().validationSuccessful).isTrue();
            assertThat(counts(jdbc)).isEqualTo(countsBefore);
            assertThat(deliveryFingerprint(jdbc)).isEqualTo(deliveriesBefore);
            // V3 added a NOT NULL flag: attempts written before it read as "not reported late".
            assertThat(jdbc.sql("SELECT bool_or(reported_after_lease_expiry) FROM delivery_attempts")
                    .query(Boolean.class).single()).isFalse();
            // V4 added users.last_login_at (nullable) and the session tables.
            assertThat(jdbc.sql("SELECT count(*) FROM users WHERE last_login_at IS NULL").query(Long.class).single())
                    .isEqualTo(fromVersion >= 4 ? 0L : 1L);
            // V5 indexes exist and the old rows are usable through them.
            assertThat(jdbc.sql("SELECT count(*) FROM pg_indexes WHERE indexname IN ('deliveries_endpoint_due_idx', "
                    + "'deliveries_endpoint_dead_lettered_idx', 'endpoints_circuit_idx', 'api_keys_active_idx')")
                    .query(Long.class).single()).isEqualTo(4L);

            // The latest constraints still hold on top of the upgraded data.
            assertThatThrownBy(() -> jdbc.sql("""
                            INSERT INTO deliveries (account_id, event_id, endpoint_id, status, max_attempts,
                                                    next_attempt_at, replay_of_delivery_id, root_delivery_id)
                            VALUES (:a, :e, :ep, 'PENDING', 3, now(), :root, :root)
                            """).param("a", seed.account).param("e", seed.event).param("ep", seed.endpoint)
                    .param("root", seed.deadLetter).update())
                    .as("a second unfinished delivery in the dead letter's chain (one is already pending)")
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.sql("""
                            INSERT INTO deliveries (account_id, event_id, endpoint_id, status, max_attempts,
                                                    next_attempt_at, root_delivery_id)
                            VALUES (:a, :e, :ep, 'PENDING', 3, now(), gen_random_uuid())
                            """).param("a", seed.account).param("e", seed.event).param("ep", seed.endpoint).update())
                    .as("a second original delivery for the same event and endpoint")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    record Seed(UUID account, UUID endpoint, UUID event, UUID deadLetter) {}

    /** The rows a running installation of {@code version} would hold: every delivery state, a replay chain, attempts. */
    private static Seed seed(JdbcClient jdbc, int version) {
        UUID account = jdbc.sql("INSERT INTO users (email, password_hash) VALUES ('Old@Example.com', '{bcrypt}x') RETURNING id")
                .query(UUID.class).single();
        if (version >= 4) {
            jdbc.sql("UPDATE users SET last_login_at = now()").update();
        }
        jdbc.sql("INSERT INTO api_keys (account_id, name, key_prefix, key_hash) VALUES (:a, 'k', 'whk_abcdefgh', '\\x01')")
                .param("a", account).update();
        jdbc.sql("""
                        INSERT INTO api_keys (account_id, name, key_prefix, key_hash, revoked_at)
                        VALUES (:a, 'old', 'whk_zzzzzzzz', '\\x02', now())
                        """).param("a", account).update();
        UUID endpoint = jdbc.sql("""
                        INSERT INTO endpoints (account_id, name, url, event_types, secret_ciphertext, secret_prefix,
                                               circuit_state, consecutive_failures, circuit_retry_at)
                        VALUES (:a, 'orders', 'https://example.com/hook', '{order.*}', '\\x0102', 'whsec_abc',
                                'OPEN', 10, now() + interval '30 seconds')
                        RETURNING id
                        """).param("a", account).query(UUID.class).single();
        jdbc.sql("""
                        INSERT INTO endpoints (account_id, name, url, secret_ciphertext, secret_prefix, deleted_at)
                        VALUES (:a, 'orders', 'https://example.com/old', '\\x03', 'whsec_old', now())
                        """).param("a", account).update();
        UUID event = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO events (id, account_id, event_type, idempotency_key, request_hash, body, created_at)
                        VALUES (:id, :a, 'order.created', 'key-1', '\\x00', '{"id":"x"}', now())
                        """).param("id", event).param("a", account).update();
        UUID event2 = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO events (id, account_id, event_type, idempotency_key, request_hash, body, created_at)
                        VALUES (:id, :a, 'order.paid', 'key-2', '\\x00', '{"id":"y"}', now())
                        """).param("id", event2).param("a", account).update();

        UUID deadLetter = UUID.randomUUID();
        insertDelivery(jdbc, deadLetter, account, event, endpoint, "'DEAD_LETTERED'", null, deadLetter,
                "dead_letter_reason, dead_lettered_at, attempt_count", "'RETRIES_EXHAUSTED', now(), 3");
        // Its replay, still pending (the chain's one unfinished delivery).
        insertDelivery(jdbc, UUID.randomUUID(), account, event, endpoint, "'PENDING'", deadLetter, deadLetter,
                "next_attempt_at", "now()");
        UUID inFlight = UUID.randomUUID();
        insertDelivery(jdbc, inFlight, account, event2, endpoint, "'IN_FLIGHT'", null, inFlight,
                "lease_token, lease_owner, lease_expires_at, attempt_count", "gen_random_uuid(), 'w1', now(), 1");
        for (int n = 1; n <= 3; n++) {
            jdbc.sql("""
                            INSERT INTO delivery_attempts (delivery_id, account_id, endpoint_id, attempt_number, status,
                                                           outcome, retryable, request_url, response_status,
                                                           response_headers, started_at, finished_at, duration_ms)
                            VALUES (:d, :a, :ep, :n, 'FAILED', 'HTTP_SERVER_ERROR', true, 'https://example.com/hook', 503,
                                    '{"retry-after":"5"}', now(), now(), 12)
                            """).param("d", deadLetter).param("a", account).param("ep", endpoint).param("n", n).update();
        }
        jdbc.sql("""
                        INSERT INTO delivery_attempts (delivery_id, account_id, endpoint_id, attempt_number, status,
                                                       request_url, started_at)
                        VALUES (:d, :a, :ep, 1, 'STARTED', 'https://example.com/hook', now())
                        """).param("d", inFlight).param("a", account).param("ep", endpoint).update();
        if (version >= 4) {
            jdbc.sql("""
                            INSERT INTO spring_session (primary_id, session_id, creation_time, last_access_time,
                                                        max_inactive_interval, expiry_time, principal_name)
                            VALUES ('p-1', 's-1', 0, 0, 28800, 28800000, 'someone')
                            """).update();
        }
        return new Seed(account, endpoint, event, deadLetter);
    }

    private static void insertDelivery(JdbcClient jdbc, UUID id, UUID account, UUID event, UUID endpoint, String status,
            UUID replayOf, UUID root, String extraColumns, String extraValues) {
        jdbc.sql("""
                        INSERT INTO deliveries (id, account_id, event_id, endpoint_id, status, max_attempts,
                                                replay_of_delivery_id, root_delivery_id, %s)
                        VALUES (:id, :a, :e, :ep, %s, 3, :replayOf, :root, %s)
                        """.formatted(extraColumns, status, extraValues))
                .param("id", id).param("a", account).param("e", event).param("ep", endpoint)
                .param("replayOf", replayOf, java.sql.Types.OTHER).param("root", root).update();
    }

    private static Map<String, Long> counts(JdbcClient jdbc) {
        return Map.of(
                "users", count(jdbc, "users"), "api_keys", count(jdbc, "api_keys"),
                "endpoints", count(jdbc, "endpoints"), "events", count(jdbc, "events"),
                "deliveries", count(jdbc, "deliveries"), "delivery_attempts", count(jdbc, "delivery_attempts"));
    }

    private static long count(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static List<String> deliveryFingerprint(JdbcClient jdbc) {
        return jdbc.sql("""
                        SELECT concat_ws('|', id, status, attempt_count, max_attempts, dead_letter_reason,
                                         replay_of_delivery_id, root_delivery_id, lease_owner)
                        FROM deliveries ORDER BY id
                        """).query(String.class).list();
    }
}
