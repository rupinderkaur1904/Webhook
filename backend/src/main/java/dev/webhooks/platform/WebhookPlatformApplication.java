package dev.webhooks.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WebhookPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebhookPlatformApplication.class, args);
    }
}
