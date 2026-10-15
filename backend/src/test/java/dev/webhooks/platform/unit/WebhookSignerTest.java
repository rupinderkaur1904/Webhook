package dev.webhooks.platform.unit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.webhooks.platform.signing.WebhookSigner;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebhookSignerTest {

    // Test vector published by the Standard Webhooks project.
    private static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    private static final String ID = "msg_p5jXN8AQM9LWM0D4loKWxJek";
    private static final long TIMESTAMP = 1614265330L;
    private static final byte[] BODY = "{\"test\": 2432232314}".getBytes(StandardCharsets.UTF_8);

    @Test
    void matchesStandardWebhooksTestVector() {
        assertThat(WebhookSigner.sign(SECRET, ID, TIMESTAMP, BODY))
                .isEqualTo("v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=");
    }

    @Test
    void signatureChangesWithAnyInput() {
        String base = WebhookSigner.sign(SECRET, ID, TIMESTAMP, BODY);
        assertThat(WebhookSigner.sign(SECRET, ID + "x", TIMESTAMP, BODY)).isNotEqualTo(base);
        assertThat(WebhookSigner.sign(SECRET, ID, TIMESTAMP + 1, BODY)).isNotEqualTo(base);
        assertThat(WebhookSigner.sign(SECRET, ID, TIMESTAMP, "{\"test\": 1}".getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(base);
    }

    @Test
    void headerCarriesOneSignaturePerSecretSpaceSeparated() {
        String other = "whsec_" + java.util.Base64.getEncoder().encodeToString(new byte[32]);
        String header = WebhookSigner.signatureHeader(List.of(SECRET, other), ID, TIMESTAMP, BODY);
        assertThat(header.split(" ")).hasSize(2).allMatch(s -> s.startsWith("v1,"));
        assertThat(header).startsWith("v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE= ");
    }
}
