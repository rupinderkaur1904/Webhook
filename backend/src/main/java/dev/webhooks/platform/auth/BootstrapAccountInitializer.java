package dev.webhooks.platform.auth;

import dev.webhooks.platform.config.WebhookProperties;
import dev.webhooks.platform.crypto.Tokens;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the bootstrap account (and optionally a known API key) from configuration. This is how a
 * fresh installation gets its first user while self-registration is disabled.
 */
@Component
public class BootstrapAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAccountInitializer.class);

    private final WebhookProperties properties;
    private final JdbcClient jdbc;
    private final ApiKeyRepository apiKeys;
    private final PasswordEncoder passwordEncoder;

    public BootstrapAccountInitializer(WebhookProperties properties, JdbcClient jdbc, ApiKeyRepository apiKeys,
            PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.apiKeys = apiKeys;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var bootstrap = properties.bootstrap();
        if (bootstrap.email() == null || bootstrap.email().isBlank()) {
            return;
        }
        if (bootstrap.password() == null || bootstrap.password().length() < 12) {
            throw new IllegalStateException("webhooks.bootstrap.password must be at least 12 characters");
        }
        jdbc.sql("INSERT INTO users (email, password_hash) VALUES (:email, :hash) ON CONFLICT (email) DO NOTHING")
                .param("email", bootstrap.email())
                .param("hash", passwordEncoder.encode(bootstrap.password()))
                .update();
        UUID accountId = jdbc.sql("SELECT id FROM users WHERE email = :email")
                .param("email", bootstrap.email())
                .query(UUID.class)
                .single();

        String apiKey = bootstrap.apiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            if (!apiKey.startsWith(Tokens.API_KEY_PREFIX) || apiKey.length() < 36) {
                throw new IllegalStateException("webhooks.bootstrap.api-key must start with 'whk_' and be at least 36 characters");
            }
            apiKeys.insertIfAbsent(accountId, "bootstrap", apiKey.substring(0, 12), Tokens.sha256(apiKey));
        }
        log.info("Bootstrap account ready (id={})", accountId);
    }
}
