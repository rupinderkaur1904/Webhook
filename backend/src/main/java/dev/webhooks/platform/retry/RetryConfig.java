package dev.webhooks.platform.retry;

import dev.webhooks.platform.config.WebhookProperties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RetryConfig {

    @Bean
    RetryPolicy retryPolicy(WebhookProperties properties) {
        // Called from many worker threads: delegate to the per-thread generator.
        RandomGenerator random = () -> ThreadLocalRandom.current().nextLong();
        return new RetryPolicy(properties.retry(), random);
    }
}
