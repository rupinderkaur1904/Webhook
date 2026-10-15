package dev.webhooks.platform.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.webhooks.platform.config.WebhookProperties;
import dev.webhooks.platform.crypto.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static SecretCipher cipher(String key) {
        return new SecretCipher(new WebhookProperties(null, new WebhookProperties.Secrets(key), null, null, null, null, null, null, null, null, null, null));
    }

    private final SecretCipher cipher = cipher(Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void roundTripsAndNeverStoresPlaintext() {
        UUID endpoint = UUID.randomUUID();
        byte[] stored = cipher.encrypt("whsec_secret-value", endpoint);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("secret-value");
        assertThat(cipher.decrypt(stored, endpoint)).isEqualTo("whsec_secret-value");
    }

    @Test
    void ciphertextIsBoundToItsEndpoint() {
        byte[] stored = cipher.encrypt("whsec_x", UUID.randomUUID());
        assertThatThrownBy(() -> cipher.decrypt(stored, UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperingIsDetected() {
        UUID endpoint = UUID.randomUUID();
        byte[] stored = cipher.encrypt("whsec_x", endpoint);
        stored[stored.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(stored, endpoint)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sameSecretEncryptsDifferentlyEachTime() {
        UUID endpoint = UUID.randomUUID();
        assertThat(cipher.encrypt("whsec_x", endpoint)).isNotEqualTo(cipher.encrypt("whsec_x", endpoint));
    }

    @Test
    void refusesToStartWithoutAValidKey() {
        assertThatThrownBy(() -> cipher(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher(Base64.getEncoder().encodeToString(new byte[16]))).isInstanceOf(IllegalStateException.class);
    }
}
