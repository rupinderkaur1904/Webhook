package dev.webhooks.platform.observability;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Applies {@link LogMasking} to every string in structured (JSON) log events: the message, MDC
 * values and stack traces alike. Registered with {@code logging.structured.json.customizer}.
 */
public class SecretMaskingJsonMembersCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, LogMasking::mask));
    }
}
