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
        @DefaultValue Retry retry,
        @DefaultValue Ssrf ssrf,
        @DefaultValue Replay replay,
        @DefaultValue Auth auth,
        @DefaultValue Limits limits,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Circuit circuit,
        @DefaultValue Metrics metrics) {

    public WebhookProperties {
        if (worker != null && delivery != null) {
            Duration slowestAttempt = delivery.connectTimeout().plus(delivery.responseTimeout());
            if (worker.leaseDuration().compareTo(slowestAttempt.plusSeconds(5)) < 0) {
                // A lease shorter than the slowest possible attempt would let the reaper retry a delivery
                // whose request is still running, causing avoidable duplicate sends.
                throw new IllegalArgumentException("webhooks.worker.lease-duration (" + worker.leaseDuration()
                        + ") must exceed connect-timeout + response-timeout by at least 5s (" + slowestAttempt + ")");
            }
        }
    }

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
            @DefaultValue("20s") Duration shutdownTimeout,
            @DefaultValue("15s") Duration reaperInterval,
            @DefaultValue("100") int reaperBatchSize) {}

    public record Delivery(
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("15s") Duration responseTimeout,
            @DefaultValue("2048") int storedResponseBodyBytes) {}

    /**
     * Retry policy. {@code maxAttempts} is snapshotted onto each delivery when it is created, so a
     * configuration change never alters the budget of work already queued.
     */
    public record Retry(
            @DefaultValue("8") int maxAttempts,
            @DefaultValue("30s") Duration baseDelay,
            @DefaultValue("1h") Duration maxDelay,
            @DefaultValue("6h") Duration maxRetryAfter) {

        public Retry {
            if (maxAttempts < 1 || maxAttempts > 50) {
                throw new IllegalArgumentException("webhooks.retry.max-attempts must be between 1 and 50");
            }
            if (baseDelay.isNegative() || baseDelay.isZero() || maxDelay.compareTo(baseDelay) < 0) {
                throw new IllegalArgumentException("webhooks.retry delays must be positive and max-delay >= base-delay");
            }
        }
    }

    /**
     * Exact {@code host} or {@code host:port} entries that may be delivered to even though they
     * resolve to private or loopback addresses. Intended for the local simulator and tests only;
     * must be empty in production.
     */
    public record Ssrf(@DefaultValue List<String> allowedHosts) {}

    /** Manual replay of dead-lettered deliveries. Each replay chain allows at most {@code maxPerDelivery} replays. */
    public record Replay(@DefaultValue("10") int maxPerDelivery) {

        public Replay {
            if (maxPerDelivery < 1) {
                throw new IllegalArgumentException("webhooks.replay.max-per-delivery must be at least 1");
            }
        }
    }

    /**
     * Dashboard accounts. Self-registration is off unless explicitly enabled. The session cookie is
     * Secure unless {@code sessionCookieSecure} is turned off (plain-HTTP local development only).
     */
    public record Auth(@DefaultValue("false") boolean registrationEnabled,
            @DefaultValue("true") boolean sessionCookieSecure) {}

    /** Request and resource limits. */
    public record Limits(
            @DefaultValue("262144") long maxRequestBytes,
            @DefaultValue("50") int maxEndpointsPerAccount) {}

    /**
     * In-memory token buckets, per instance. Ingestion is limited per credential (API key or
     * dashboard session); login attempts per client IP.
     */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("50") int ingestPerSecond,
            @DefaultValue("100") int ingestBurst,
            @DefaultValue("5") int loginPerMinute) {}

    /**
     * Per-endpoint circuit breaker (docs/PLAN.md §7.5). {@code failureThreshold} consecutive retryable
     * failures (429 excluded) open the circuit for {@code baseCooldown × 2^(times opened)}, capped at
     * {@code maxCooldown}. Endpoint health reports {@code FAILING} from {@code failingAfter} consecutive
     * failures.
     */
    public record Circuit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("10") int failureThreshold,
            @DefaultValue("30s") Duration baseCooldown,
            @DefaultValue("30m") Duration maxCooldown,
            @DefaultValue("5") int failingAfter) {

        public Circuit {
            if (failureThreshold < 1) {
                throw new IllegalArgumentException("webhooks.circuit.failure-threshold must be at least 1");
            }
            if (baseCooldown.isNegative() || baseCooldown.isZero() || maxCooldown.compareTo(baseCooldown) < 0) {
                throw new IllegalArgumentException("webhooks.circuit cool-downs must be positive and max-cooldown >= base-cooldown");
            }
        }
    }

    /**
     * Prometheus scraping. {@code GET /actuator/prometheus} requires {@code Authorization: Bearer <token>};
     * without a token configured the endpoint refuses every request.
     */
    public record Metrics(String token) {

        public Metrics {
            if (token != null && !token.isBlank() && token.length() < 24) {
                throw new IllegalArgumentException("webhooks.metrics.token (METRICS_TOKEN) must be at least 24 characters");
            }
        }

        public boolean scrapingEnabled() {
            return token != null && !token.isBlank();
        }
    }
}
