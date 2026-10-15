package dev.webhooks.platform.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * All platform settings, bound from {@code webhooks.*}. Every value can be overridden
 * with an environment variable (e.g. {@code WEBHOOKS_WORKER_ENABLED=false}).
 */
@ConfigurationProperties("webhooks")
public record WebhookProperties(
        @DefaultValue Bootstrap bootstrap,
        @DefaultValue Secrets secrets,
        @DefaultValue Worker worker,
        @DefaultValue Delivery delivery,
        @DefaultValue Ssrf ssrf) {

    /** Optional account and API key created at startup, for local use before sign-up exists. */
    public record Bootstrap(String email, String password, String apiKey) {}

    /** Base64-encoded 32-byte AES key used to encrypt endpoint signing secrets at rest. */
    public record Secrets(String encryptionKey) {}

    public record Worker(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("500ms") Duration pollInterval,
            @DefaultValue("32") int batchSize,
            @DefaultValue("32") int maxConcurrency,
            @DefaultValue("60s") Duration leaseDuration,
            @DefaultValue("20s") Duration shutdownTimeout) {}

    public record Delivery(
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("15s") Duration responseTimeout,
            @DefaultValue("2048") int storedResponseBodyBytes,
            @DefaultValue("1") int maxAttempts) {}

    /**
     * Exact {@code host} or {@code host:port} entries that may be delivered to even though they
     * resolve to private or loopback addresses. Intended for the local simulator and tests only;
     * must be empty in production.
     */
    public record Ssrf(@DefaultValue List<String> allowedHosts) {}
}
