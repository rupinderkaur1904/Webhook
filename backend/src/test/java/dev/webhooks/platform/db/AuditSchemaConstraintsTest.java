package dev.webhooks.platform.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.webhooks.platform.support.IntegrationTest;
import dev.webhooks.platform.support.ScratchDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * Every unique, foreign-key and check constraint of the latest schema, violated with direct SQL. Each
 * case runs in its own rolled-back transaction on a scratch database seeded with one valid row of
 * each kind, and each has a passing twin (same statement, valid value) so the failure is known to come
 * from the constraint under test and not from an unrelated mistake in the statement.
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditSchemaConstraintsTest {

    static final String U = "'00000000-0000-0000-0000-00000000000a'";
    static final String U2 = "'00000000-0000-0000-0000-00000000000b'";
    static final String EP = "'00000000-0000-0000-0000-0000000000e1'";
    static final String EV = "'00000000-0000-0000-0000-0000000000f1'";
    static final String EV2 = "'00000000-0000-0000-0000-0000000000f2'";
    static final String D = "'00000000-0000-0000-0000-0000000000d1'";

    @Autowired DataSource dataSource;
    @Autowired Environment env;

    ScratchDatabase db;

    @BeforeAll
    void createSchema() {
        db = new ScratchDatabase(dataSource, env, "audit_constraints");
        db.migrateTo("5");
        var jdbc = db.jdbc();
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (" + U + ", 'a@example.com', 'x')").update();
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (" + U2 + ", 'b@example.com', 'x')").update();
        jdbc.sql("INSERT INTO api_keys (account_id, name, key_prefix, key_hash) VALUES (" + U + ", 'k', 'p', '\\x01')").update();
        jdbc.sql("INSERT INTO endpoints (id, account_id, name, url, secret_ciphertext, secret_prefix) VALUES ("
                + EP + ", " + U + ", 'ep', 'https://example.com', '\\x00', 'whsec_')").update();
        jdbc.sql("INSERT INTO events (id, account_id, event_type, idempotency_key, request_hash, body, created_at) VALUES ("
                + EV + ", " + U + ", 't', 'k1', '\\x00', '{}', now())").update();
        jdbc.sql("INSERT INTO events (id, account_id, event_type, idempotency_key, request_hash, body, created_at) VALUES ("
                + EV2 + ", " + U + ", 't', 'k2', '\\x00', '{}', now())").update();
        jdbc.sql("INSERT INTO deliveries (id, account_id, event_id, endpoint_id, status, max_attempts, next_attempt_at, "
                + "root_delivery_id) VALUES (" + D + ", " + U + ", " + EV + ", " + EP + ", 'PENDING', 3, now(), " + D + ")").update();
        jdbc.sql("INSERT INTO delivery_attempts (delivery_id, account_id, endpoint_id, attempt_number, status, request_url, "
                + "started_at) VALUES (" + D + ", " + U + ", " + EP + ", 1, 'FAILED', 'u', now())").update();
    }

    @AfterAll
    void dropSchema() {
        db.close();
    }

    static String delivery(String id, String event, String status, String extraCols, String extraVals) {
        return "INSERT INTO deliveries (id, account_id, event_id, endpoint_id, status, max_attempts, root_delivery_id"
                + extraCols + ") VALUES (" + id + ", " + U + ", " + event + ", " + EP + ", " + status + ", 3, " + id
                + extraVals + ")";
    }

    static String attempt(String number, String status) {
        return "INSERT INTO delivery_attempts (delivery_id, account_id, endpoint_id, attempt_number, status, request_url, "
                + "started_at) VALUES (" + D + ", " + U + ", " + EP + ", " + number + ", " + status + ", 'u', now())";
    }

    static String endpoint(String name, String url, String description, String circuit, String deletedAt) {
        return "INSERT INTO endpoints (account_id, name, url, description, circuit_state, secret_ciphertext, secret_prefix, "
                + "deleted_at) VALUES (" + U + ", " + name + ", " + url + ", " + description + ", " + circuit
                + ", '\\x00', 'whsec_', " + deletedAt + ")";
    }

    static String event(String account, String type, String key) {
        return "INSERT INTO events (id, account_id, event_type, idempotency_key, request_hash, body, created_at) VALUES "
                + "(gen_random_uuid(), " + account + ", " + type + ", " + key + ", '\\x00', '{}', now())";
    }

    static final String NEW = "gen_random_uuid()";

    /** name, violating statement, the same statement with a valid value. */
    static Stream<Arguments> constraints() {
        return Stream.of(
                Arguments.of("users.email unique, case-insensitive (citext)",
                        "INSERT INTO users (email, password_hash) VALUES ('A@Example.COM', 'x')",
                        "INSERT INTO users (email, password_hash) VALUES ('c@example.com', 'x')"),
                Arguments.of("users.email not null",
                        "INSERT INTO users (email, password_hash) VALUES (NULL, 'x')",
                        "INSERT INTO users (email, password_hash) VALUES ('d@example.com', 'x')"),
                Arguments.of("api_keys.key_hash unique",
                        "INSERT INTO api_keys (account_id, name, key_prefix, key_hash) VALUES (" + U2 + ", 'k', 'p', '\\x01')",
                        "INSERT INTO api_keys (account_id, name, key_prefix, key_hash) VALUES (" + U2 + ", 'k', 'p', '\\x02')"),
                Arguments.of("api_keys.account_id references users",
                        "INSERT INTO api_keys (account_id, name, key_prefix, key_hash) VALUES (" + NEW + ", 'k', 'p', '\\x03')",
                        "INSERT INTO api_keys (account_id, name, key_prefix, key_hash) VALUES (" + U + ", 'k', 'p', '\\x03')"),
                Arguments.of("api_keys.name length 1..100",
                        "INSERT INTO api_keys (account_id, name, key_prefix, key_hash) VALUES (" + U + ", '', 'p', '\\x04')",
                        "INSERT INTO api_keys (account_id, name, key_prefix, key_hash) VALUES (" + U + ", repeat('n', 100), 'p', '\\x04')"),
                Arguments.of("endpoints live name unique per account",
                        endpoint("'ep'", "'https://e'", "NULL", "'CLOSED'", "NULL"),
                        endpoint("'ep'", "'https://e'", "NULL", "'CLOSED'", "now()")),
                Arguments.of("endpoints.name length 1..100",
                        endpoint("repeat('n', 101)", "'https://e'", "NULL", "'CLOSED'", "NULL"),
                        endpoint("repeat('n', 100)", "'https://e'", "NULL", "'CLOSED'", "NULL")),
                Arguments.of("endpoints.url length <= 2048",
                        endpoint("'u'", "repeat('u', 2049)", "NULL", "'CLOSED'", "NULL"),
                        endpoint("'u'", "repeat('u', 2048)", "NULL", "'CLOSED'", "NULL")),
                Arguments.of("endpoints.description length <= 500",
                        endpoint("'d'", "'https://e'", "repeat('d', 501)", "'CLOSED'", "NULL"),
                        endpoint("'d'", "'https://e'", "repeat('d', 500)", "'CLOSED'", "NULL")),
                Arguments.of("endpoints.circuit_state enum",
                        endpoint("'c'", "'https://e'", "NULL", "'BROKEN'", "NULL"),
                        endpoint("'c'", "'https://e'", "NULL", "'HALF_OPEN'", "NULL")),
                Arguments.of("events idempotency key unique per account",
                        event(U, "'t'", "'k1'"), event(U2, "'t'", "'k1'")),
                Arguments.of("events.event_type length 1..100",
                        event(U, "repeat('t', 101)", "'k3'"), event(U, "repeat('t', 100)", "'k3'")),
                Arguments.of("events.idempotency_key length 1..255",
                        event(U, "'t'", "repeat('k', 256)"), event(U, "'t'", "repeat('k', 255)")),
                Arguments.of("events.account_id references users",
                        event(NEW, "'t'", "'k4'"), event(U, "'t'", "'k4'")),
                Arguments.of("deliveries.status enum",
                        delivery(NEW, EV2, "'LOST'", "", ""),
                        delivery(NEW, EV2, "'CANCELLED'", "", "")),
                Arguments.of("deliveries.attempt_count >= 0",
                        delivery(NEW, EV2, "'CANCELLED'", ", attempt_count", ", -1"),
                        delivery(NEW, EV2, "'CANCELLED'", ", attempt_count", ", 0")),
                Arguments.of("deliveries.max_attempts >= 1",
                        "INSERT INTO deliveries (account_id, event_id, endpoint_id, status, max_attempts, root_delivery_id) "
                                + "VALUES (" + U + ", " + EV2 + ", " + EP + ", 'CANCELLED', 0, " + NEW + ")",
                        "INSERT INTO deliveries (account_id, event_id, endpoint_id, status, max_attempts, root_delivery_id) "
                                + "VALUES (" + U + ", " + EV2 + ", " + EP + ", 'CANCELLED', 1, " + NEW + ")"),
                Arguments.of("deliveries.dead_letter_reason enum",
                        delivery(NEW, EV2, "'DEAD_LETTERED'", ", dead_letter_reason", ", 'BORED'"),
                        delivery(NEW, EV2, "'DEAD_LETTERED'", ", dead_letter_reason", ", 'DESTINATION_BLOCKED'")),
                Arguments.of("deliveries_schedule_ck: PENDING/RETRYING need next_attempt_at",
                        delivery(NEW, EV2, "'RETRYING'", "", ""),
                        delivery(NEW, EV2, "'RETRYING'", ", next_attempt_at", ", now()")),
                Arguments.of("deliveries_lease_ck: IN_FLIGHT needs a lease token and expiry",
                        delivery(NEW, EV2, "'IN_FLIGHT'", ", lease_token", ", gen_random_uuid()"),
                        delivery(NEW, EV2, "'IN_FLIGHT'", ", lease_token, lease_expires_at", ", gen_random_uuid(), now()")),
                Arguments.of("deliveries_dead_letter_ck: DEAD_LETTERED needs a reason",
                        delivery(NEW, EV2, "'DEAD_LETTERED'", "", ""),
                        delivery(NEW, EV2, "'DEAD_LETTERED'", ", dead_letter_reason", ", 'RETRIES_EXHAUSTED'")),
                Arguments.of("deliveries_original_uq: one original per (event, endpoint)",
                        delivery(NEW, EV, "'CANCELLED'", "", ""),
                        delivery(NEW, EV, "'CANCELLED'", ", replay_of_delivery_id", ", " + D)),
                Arguments.of("deliveries_active_chain_uq: one unfinished delivery per chain",
                        "INSERT INTO deliveries (account_id, event_id, endpoint_id, status, max_attempts, next_attempt_at, "
                                + "replay_of_delivery_id, root_delivery_id) VALUES (" + U + ", " + EV + ", " + EP
                                + ", 'PENDING', 3, now(), " + D + ", " + D + ")",
                        "INSERT INTO deliveries (account_id, event_id, endpoint_id, status, max_attempts, next_attempt_at, "
                                + "replay_of_delivery_id, root_delivery_id) VALUES (" + U + ", " + EV + ", " + EP
                                + ", 'DELIVERED', 3, NULL, " + D + ", " + D + ")"),
                Arguments.of("deliveries.endpoint_id references endpoints",
                        "INSERT INTO deliveries (account_id, event_id, endpoint_id, status, max_attempts, root_delivery_id) "
                                + "VALUES (" + U + ", " + EV2 + ", " + NEW + ", 'CANCELLED', 1, " + NEW + ")",
                        "INSERT INTO deliveries (account_id, event_id, endpoint_id, status, max_attempts, root_delivery_id) "
                                + "VALUES (" + U + ", " + EV2 + ", " + EP + ", 'CANCELLED', 1, " + NEW + ")"),
                Arguments.of("deliveries.event_id references events",
                        delivery(NEW, NEW, "'CANCELLED'", "", ""), delivery(NEW, EV2, "'CANCELLED'", "", "")),
                Arguments.of("deliveries.replay_of_delivery_id references deliveries",
                        delivery(NEW, EV, "'CANCELLED'", ", replay_of_delivery_id", ", " + NEW),
                        delivery(NEW, EV, "'CANCELLED'", ", replay_of_delivery_id", ", " + D)),
                Arguments.of("deliveries.replayed_by_user_id references users",
                        delivery(NEW, EV2, "'CANCELLED'", ", replayed_by_user_id", ", " + NEW),
                        delivery(NEW, EV2, "'CANCELLED'", ", replayed_by_user_id", ", " + U)),
                Arguments.of("delivery_attempts unique (delivery, attempt_number)",
                        attempt("1", "'FAILED'"), attempt("2", "'FAILED'")),
                Arguments.of("delivery_attempts.attempt_number >= 1",
                        attempt("0", "'FAILED'"), attempt("3", "'FAILED'")),
                Arguments.of("delivery_attempts.status enum",
                        attempt("4", "'MAYBE'"), attempt("4", "'ABANDONED'")),
                Arguments.of("delivery_attempts.delivery_id references deliveries",
                        "INSERT INTO delivery_attempts (delivery_id, account_id, endpoint_id, attempt_number, status, "
                                + "request_url, started_at) VALUES (" + NEW + ", " + U + ", " + EP + ", 1, 'FAILED', 'u', now())",
                        attempt("5", "'FAILED'")),
                Arguments.of("spring_session_attributes reference spring_session",
                        "INSERT INTO spring_session_attributes VALUES ('missing', 'a', '\\x00')",
                        "INSERT INTO spring_session VALUES ('p', 's', 0, 0, 1, 1, NULL); "
                                + "INSERT INTO spring_session_attributes VALUES ('p', 'a', '\\x00')"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("constraints")
    void constraintRejectsTheViolationAndAcceptsTheValidTwin(String name, String violating, String valid) throws Exception {
        assertThatThrownBy(() -> runRolledBack(violating)).as(name).isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).as("integrity violation class 23")
                        .startsWith("23"));
        assertThatCode(() -> runRolledBack(valid)).as(name + " (valid twin)").doesNotThrowAnyException();
    }

    @Test
    void deletingAnAccountCascadesThroughAllOfItsData() throws Exception {
        try (Connection c = db.dataSource().getConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("DELETE FROM users WHERE id = " + U);
            try (var rs = s.executeQuery("SELECT (SELECT count(*) FROM deliveries) + (SELECT count(*) FROM delivery_attempts)"
                    + " + (SELECT count(*) FROM events) + (SELECT count(*) FROM endpoints) + (SELECT count(*) FROM api_keys)")) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }
            c.rollback();
        }
    }

    @Test
    void anEndpointWithDeliveriesCannotBeHardDeleted() {
        // Endpoints are soft-deleted (deleted_at); the history keeps referencing them.
        assertThatThrownBy(() -> runRolledBack("DELETE FROM endpoints WHERE id = " + EP))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23503"));
    }

    private void runRolledBack(String sql) throws SQLException {
        try (Connection c = db.dataSource().getConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                s.execute(sql);
            } finally {
                c.rollback();
            }
        }
    }
}
