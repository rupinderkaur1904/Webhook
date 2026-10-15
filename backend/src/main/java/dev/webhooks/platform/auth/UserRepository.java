package dev.webhooks.platform.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    public record User(UUID id, String email, String passwordHash) {}

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<User> findByEmail(String email) {
        return jdbc.sql("SELECT id, email, password_hash FROM users WHERE email = :email")
                .param("email", email)
                .query((rs, n) -> new User(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("password_hash")))
                .optional();
    }

    public Optional<User> find(UUID id) {
        return jdbc.sql("SELECT id, email, password_hash FROM users WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new User(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("password_hash")))
                .optional();
    }

    /** Returns the new user's id, or empty if the email is taken. */
    public Optional<UUID> insert(String email, String passwordHash) {
        try {
            return Optional.of(jdbc.sql("INSERT INTO users (email, password_hash) VALUES (:email, :hash) RETURNING id")
                    .param("email", email)
                    .param("hash", passwordHash)
                    .query(UUID.class)
                    .single());
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    public void recordLogin(UUID id, String newPasswordHash) {
        jdbc.sql("""
                        UPDATE users SET last_login_at = now(), password_hash = COALESCE(:hash, password_hash)
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("hash", newPasswordHash)
                .update();
    }
}
