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
 * Behind a trusted proxy. The test client connects from 127.0.0.1, which is configured as the
 * proxy, so it plays the proxy's part: the last X-Forwarded-For entry is what the proxy saw. The
 * login limit (3 per minute per address) shows which address the server decided the client has.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "webhooks.proxy.trusted-proxies=127.0.0.1", "webhooks.rate-limit.login-per-minute=3"})
@ActiveProfiles("test")
@ContextConfiguration(initializers = PostgresInitializer.class)
class TrustedProxyTest {

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    ForwardedClient client;

    @BeforeEach
    void setUp() {
        client = new ForwardedClient(port, json);
    }

    @Test
    void eachForwardedClientAddressGetsItsOwnLoginLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(client.failedLogin("203.0.113.10")).isEqualTo(401);
        }
        assertThat(client.failedLogin("203.0.113.10")).isEqualTo(429);
        // A different visitor behind the same proxy is not affected by the first one's limit.
        assertThat(client.failedLogin("203.0.113.11")).isEqualTo(401);
    }

    @Test
    void addressesTheClientPrependsAreIgnored() throws Exception {
        // The client sends its own X-Forwarded-For; the proxy appends the real peer (203.0.113.20).
        // Rotating the spoofed part must not escape the limit for the real address.
        for (int i = 0; i < 3; i++) {
            assertThat(client.failedLogin("198.51.100." + i + ", 203.0.113.20")).isEqualTo(401);
        }
        assertThat(client.failedLogin("198.51.100.99, 203.0.113.20")).isEqualTo(429);
    }

    @Test
    void forwardedHttpsFromTheProxyMakesTheRequestSecure() throws Exception {
        assertThat(client.get("/api/v1/auth/csrf", "203.0.113.30", "https").headers()
                .firstValue("Strict-Transport-Security")).isPresent();
        assertThat(client.get("/api/v1/auth/csrf", "203.0.113.30", null).headers()
                .firstValue("Strict-Transport-Security")).isEmpty();
    }
}
