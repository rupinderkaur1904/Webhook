package dev.webhooks.platform.db;

import static org.assertj.core.api.Assertions.assertThat;

import dev.webhooks.platform.support.IntegrationTest;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class MigrationTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Test
    void migratesAnEmptyDatabaseFromScratch() {
        Flyway flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        var result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(flyway.info().applied().length);
        List<String> tables = jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
                        ORDER BY table_name
                        """)
                .query(String.class)
                .list();
        assertThat(tables).contains("users", "api_keys", "endpoints", "events", "deliveries", "delivery_attempts");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void schemaRejectsInvalidDeliveryStates() {
        var account = jdbc.sql("INSERT INTO users (email, password_hash) VALUES (gen_random_uuid()::text, 'x') RETURNING id")
                .query(java.util.UUID.class).single();
        var endpoint = jdbc.sql("""
                        INSERT INTO endpoints (account_id, name, url, secret_ciphertext, secret_prefix)
                        VALUES (:a, 'e', 'https://example.com', '\\x00', 'whsec_')
                        RETURNING id
                        """).param("a", account).query(java.util.UUID.class).single();
        var event = java.util.UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO events (id, account_id, event_type, idempotency_key, request_hash, body, created_at)
                        VALUES (:id, :a, 't', 'k', '\\x00', '{}', now())
                        """).param("id", event).param("a", account).update();

        // IN_FLIGHT without a lease, PENDING without a schedule, DEAD_LETTERED without a reason.
        for (String values : List.of(
                "'IN_FLIGHT', NULL, NULL", "'PENDING', NULL, NULL", "'DEAD_LETTERED', NULL, NULL")) {
            var id = java.util.UUID.randomUUID();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                            INSERT INTO deliveries (id, account_id, event_id, endpoint_id, status, next_attempt_at,
                                                    dead_letter_reason, max_attempts, root_delivery_id)
                            VALUES (:id, :a, :e, :ep, %s, 1, :id)
                            """.formatted(values))
                    .param("id", id).param("a", account).param("e", event).param("ep", endpoint).update())
                    .as(values)
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }
}
