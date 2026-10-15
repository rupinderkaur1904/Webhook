package dev.webhooks.platform.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.webhooks.platform.config.WebhookProperties;
import dev.webhooks.platform.ssrf.DestinationPolicy;
import dev.webhooks.platform.ssrf.DestinationPolicy.InvalidDestinationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DestinationPolicyTest {

    private final DestinationPolicy policy = new DestinationPolicy(new WebhookProperties(null, null, null, null,
            new WebhookProperties.Ssrf(List.of("simulator:9090"))));

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/hook", "https://[::1]/hook", "https://169.254.169.254/latest/meta-data",
            "https://10.0.0.5:8443/", "https://[::ffff:127.0.0.1]/", "https://2130706433/", "https://0x7f.1/",
            "https://localhost/hook", "http://example.com/hook", "ftp://example.com/", "https://user:pw@example.com/",
            "https://example.com/#frag", "not a url", "https:///nohost", "https://simulator:22/"})
    void rejectsUnsafeOrMalformedDestinations(String url) {
        assertThatThrownBy(() -> policy.validateForRegistration(url)).isInstanceOf(InvalidDestinationException.class);
    }

    @Test
    void allowsAllowlistedHostOnItsPortOverHttp() throws Exception {
        // "simulator" does not resolve here, but allowlisted hosts skip registration-time resolution.
        assertThat(policy.validateForRegistration("http://simulator:9090/receivers/a").getHost()).isEqualTo("simulator");
    }

    @Test
    void sendTimeCheckRejectsLiteralPrivateAddressesWithoutDns() {
        assertThatThrownBy(() -> policy.validateForSend("https://192.168.0.10/")).isInstanceOf(InvalidDestinationException.class);
    }
}
