package dev.webhooks.platform.auth;

import static dev.webhooks.platform.common.Jdbc.instant;

import java.time.Instant;
import java.util.List;
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

    public record ApiKey(UUID id, String name, String prefix, Instant createdAt, Instant lastUsedAt, Instant revokedAt) {}

    public List<ApiKey> list(UUID accountId) {
        return jdbc.sql("""
                        SELECT id, name, key_prefix, created_at, last_used_at, revoked_at FROM api_keys
                        WHERE account_id = :account ORDER BY revoked_at IS NOT NULL, created_at DESC
                        """)
                .param("account", accountId)
                .query((rs, n) -> new ApiKey(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("key_prefix"), instant(rs, "created_at"), instant(rs, "last_used_at"),
                        instant(rs, "revoked_at")))
                .list();
    }

    public int countActive(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM api_keys WHERE account_id = :account AND revoked_at IS NULL")
                .param("account", accountId)
                .query(Integer.class)
                .single();
    }

    public UUID insert(UUID accountId, String name, String keyPrefix, byte[] keyHash) {
        return jdbc.sql("""
                        INSERT INTO api_keys (account_id, name, key_prefix, key_hash)
                        VALUES (:account, :name, :prefix, :hash) RETURNING id
                        """)
                .param("account", accountId)
                .param("name", name)
                .param("prefix", keyPrefix)
                .param("hash", keyHash)
                .query(UUID.class)
                .single();
    }

    /** Revokes an active key; returns false if it does not exist for this account or is already revoked. */
    public boolean revoke(UUID accountId, UUID id) {
        return jdbc.sql("""
                        UPDATE api_keys SET revoked_at = now()
                        WHERE id = :id AND account_id = :account AND revoked_at IS NULL
                        """)
                .param("id", id)
                .param("account", accountId)
                .update() == 1;
    }
}
