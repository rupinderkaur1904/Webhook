package dev.webhooks.platform.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.webhooks.platform.config.WebhookProperties;
import dev.webhooks.platform.ratelimit.RateLimitedException;
import dev.webhooks.platform.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    static RateLimiter limiter(boolean enabled, int perSecond, int burst, int loginPerMinute) {
        return new RateLimiter(new WebhookProperties(null, null, null, null, null, null, null, null, null,
                new WebhookProperties.RateLimit(enabled, perSecond, burst, loginPerMinute)));
    }

    @Test
    void ingestionAllowsTheBurstThenRefusesWithARetryAfter() {
        RateLimiter limiter = limiter(true, 1, 3, 5);
        for (int i = 0; i < 3; i++) {
            limiter.checkIngest("api-key:a");
        }
        assertThatThrownBy(() -> limiter.checkIngest("api-key:a"))
                .isInstanceOfSatisfying(RateLimitedException.class, e -> {
                    assertThat(e.retryAfterSeconds()).isBetween(1L, 2L);
                    assertThat(e.code()).isEqualTo("RATE_LIMITED");
                });
        // Buckets are per credential.
        assertThatCode(() -> limiter.checkIngest("api-key:b")).doesNotThrowAnyException();
    }

    @Test
    void loginAllowsFiveAttemptsAMinutePerKey() {
        RateLimiter limiter = limiter(true, 50, 100, 5);
        for (int i = 0; i < 5; i++) {
            limiter.checkLogin("ip:10.0.0.1");
        }
        assertThatThrownBy(() -> limiter.checkLogin("ip:10.0.0.1"))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        e -> assertThat(e.retryAfterSeconds()).isBetween(50L, 60L));
        assertThatCode(() -> limiter.checkLogin("ip:10.0.0.2")).doesNotThrowAnyException();
    }

    @Test
    void disabledLimiterNeverRefuses() {
        RateLimiter limiter = limiter(false, 1, 1, 1);
        for (int i = 0; i < 20; i++) {
            limiter.checkIngest("k");
            limiter.checkLogin("ip");
        }
    }
}
