package dev.webhooks.platform.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApiKeyRepository {

    private final JdbcClient jdbc;

    public ApiKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AccountPrincipal> findActiveByHash(byte[] keyHash) {
        return jdbc.sql("""
                        SELECT id, account_id FROM api_keys
                        WHERE key_hash = :hash AND revoked_at IS NULL
                        """)
                .param("hash", keyHash)
                .query((rs, n) -> new AccountPrincipal(
                        rs.getObject("account_id", UUID.class), "api-key:" + rs.getObject("id", UUID.class)))
                .optional();
    }

    /** Records usage at most once a minute per key, to avoid a write on every request. */
    public void touch(UUID keyId) {
        jdbc.sql("""
                        UPDATE api_keys SET last_used_at = now()
                        WHERE id = :id AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute')
                        """)
                .param("id", keyId)
                .update();
    }

    public void insertIfAbsent(UUID accountId, String name, String keyPrefix, byte[] keyHash) {
        jdbc.sql("""
                        INSERT INTO api_keys (account_id, name, key_prefix, key_hash)
                        VALUES (:account, :name, :prefix, :hash)
                        ON CONFLICT (key_hash) DO NOTHING
                        """)
                .param("account", accountId)
                .param("name", name)
                .param("prefix", keyPrefix)
                .param("hash", keyHash)
                .update();
    }
}
