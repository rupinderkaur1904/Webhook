package dev.webhooks.platform.ratelimit;

import dev.webhooks.platform.config.WebhookProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * In-memory token buckets (Bucket4j). Limits are per instance, not cluster-wide: with N instances a
 * client can get up to N times the configured rate. That is an accepted limitation (docs/PLAN.md §8);
 * the limits exist to stop accidental floods and password guessing, not to meter usage.
 */
@Component
public class RateLimiter {

    static final int EVICTION_CHECK_EVERY = 10_000;

    private final WebhookProperties.RateLimit config;
    private final Limit ingest;
    private final Limit login;
    private final AtomicLong calls = new AtomicLong();

    public RateLimiter(WebhookProperties properties) {
        this.config = properties.rateLimit();
        this.ingest = new Limit(Bandwidth.builder().capacity(config.ingestBurst())
                .refillGreedy(config.ingestPerSecond(), Duration.ofSeconds(1)).build(),
                "Too many events submitted; slow down");
        this.login = new Limit(Bandwidth.builder().capacity(config.loginPerMinute())
                .refillIntervally(config.loginPerMinute(), Duration.ofMinutes(1)).build(),
                "Too many login attempts; try again later");
    }

    /** Event ingestion, per credential (API key or dashboard session). */
    public void checkIngest(String credentialId) {
        check(ingest, credentialId);
    }

    /** Login attempts, per client address. */
    public void checkLogin(String clientAddress) {
        check(login, clientAddress);
    }

    private void check(Limit limit, String key) {
        if (!config.enabled()) {
            return;
        }
        if (calls.incrementAndGet() % EVICTION_CHECK_EVERY == 0) {
            limit.evictIdle();
        }
        ConsumptionProbe probe = limit.bucket(key).tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long seconds = Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
            throw new RateLimitedException(limit.message, seconds);
        }
    }

    private static final class Limit {

        private final Bandwidth bandwidth;
        private final String message;
        private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

        Limit(Bandwidth bandwidth, String message) {
            this.bandwidth = bandwidth;
            this.message = message;
        }

        Bucket bucket(String key) {
            return buckets.computeIfAbsent(key, k -> Bucket.builder().addLimit(bandwidth).build());
        }

        /** Drops buckets that have refilled completely: forgetting them changes nothing for their clients. */
        void evictIdle() {
            buckets.values().removeIf(bucket -> bucket.getAvailableTokens() >= bandwidth.getCapacity());
        }
    }
}
