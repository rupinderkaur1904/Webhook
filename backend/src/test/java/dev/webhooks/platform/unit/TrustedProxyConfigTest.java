package dev.webhooks.platform.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.webhooks.platform.config.TrustedProxyConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TrustedProxyConfigTest {

    @Test
    void singleAddressesBecomeHostRangesAndBlanksAreDropped() {
        assertThat(TrustedProxyConfig.normalize(List.of(" 10.0.0.0/8 ", "192.0.2.7", "", "fd00::1")))
                .containsExactly("10.0.0.0/8", "192.0.2.7/32", "fd00::1/128");
    }

    @Test
    void nothingConfiguredMeansNothingTrusted() {
        assertThat(TrustedProxyConfig.normalize(null)).isEmpty();
        assertThat(TrustedProxyConfig.normalize(List.of())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"proxy.internal", "10.0.0.0/33", "10.0.0/8x", "*", "0.0.0.0/abc"})
    void invalidEntriesFailStartup(String entry) {
        assertThatThrownBy(() -> TrustedProxyConfig.normalize(List.of(entry)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trusted-proxies");
    }
}
