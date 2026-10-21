package dev.webhooks.platform.unit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.webhooks.platform.observability.LogMasking;
import dev.webhooks.platform.observability.SecretMaskingJsonMembersCustomizer;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.json.JsonWriter;

class LogMaskingTest {

    @Test
    void masksSecretsAndApiKeysAnywhereInText() {
        assertThat(LogMasking.mask("secret whsec_dGVzdC1zZWNyZXQ+/= and key whk_AbC123_-x, done"))
                .isEqualTo("secret whsec_**** and key whk_****, done");
        assertThat(LogMasking.mask("no secrets_here")).isEqualTo("no secrets_here");
        assertThat(LogMasking.mask(null)).isNull();
    }

    @Test
    void structuredLogCustomizerMasksEveryStringMember() {
        JsonWriter<Map<String, String>> writer = JsonWriter.of(members -> {
            members.add("message", m -> m.get("message"));
            members.add("mdc", m -> m.get("mdc"));
            members.add("stack", m -> m.get("stack"));
            @SuppressWarnings({"unchecked", "rawtypes"})
            JsonWriter.Members<Object> raw = (JsonWriter.Members) members;
            new SecretMaskingJsonMembersCustomizer().customize(raw);
        });
        String json = writer.writeToString(Map.of(
                "message", "rotated to whsec_c2VjcmV0",
                "mdc", "whk_live_key_value",
                "stack", "IllegalStateException: bad whsec_abc\n\tat x"));
        assertThat(json).doesNotContain("c2VjcmV0", "live_key_value", "whsec_abc")
                .contains("whsec_****", "whk_****");
    }
}
