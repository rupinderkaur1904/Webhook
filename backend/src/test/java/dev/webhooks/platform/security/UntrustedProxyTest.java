package dev.webhooks.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.webhooks.platform.support.PostgresInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.ObjectMapper;

/**
 * A proxy range is configured (10.0.0.0/8), but the test client connects from 127.0.0.1, outside
 * it: an attacker reaching the app directly. Its forwarding headers must be ignored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "webhooks.proxy.trusted-proxies=10.0.0.0/8", "webhooks.rate-limit.login-per-minute=3"})
@ActiveProfiles("test")
@ContextConfiguration(initializers = PostgresInitializer.class)
class UntrustedProxyTest {

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    ForwardedClient client;

    @BeforeEach
    void setUp() {
        client = new ForwardedClient(port, json);
    }

    @Test
    void spoofedForwardedForFromAnUntrustedPeerCannotDodgeTheLoginLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(client.failedLogin("203.0.113." + i)).isEqualTo(401);
        }
        // Still the same client (127.0.0.1) however the header changes.
        assertThat(client.failedLogin("203.0.113.99")).isEqualTo(429);
        assertThat(client.failedLogin("10.1.2.3")).isEqualTo(429);
    }

    @Test
    void spoofedForwardedProtoDoesNotMakeTheRequestSecure() throws Exception {
        assertThat(client.get("/api/v1/auth/csrf", null, "https").headers()
                .firstValue("Strict-Transport-Security")).isEmpty();
    }
}
