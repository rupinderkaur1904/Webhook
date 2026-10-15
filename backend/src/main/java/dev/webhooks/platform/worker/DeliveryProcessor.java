package dev.webhooks.platform.worker;

import dev.webhooks.platform.crypto.SecretCipher;
import dev.webhooks.platform.delivery.ClaimedDelivery;
import dev.webhooks.platform.delivery.DeadLetterReason;
import dev.webhooks.platform.delivery.DeliveryRepository;
import dev.webhooks.platform.delivery.DeliveryRepository.AttemptRecord;
import dev.webhooks.platform.endpoint.EndpointRepository;
import dev.webhooks.platform.signing.WebhookSigner;
import dev.webhooks.platform.ssrf.DestinationPolicy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Performs one claimed delivery attempt: sign, send, and record the result.
 *
 * <p>No database transaction is open while the HTTP request is in flight. The result is written in
 * one short transaction; the delivery row is only updated if it still carries this attempt's lease
 * token, so a stale worker cannot overwrite newer state.
 */
@Component
public class DeliveryProcessor {

    private static final Logger log = LoggerFactory.getLogger(DeliveryProcessor.class);

    private final JdbcClient jdbc;
    private final EndpointRepository endpoints;
    private final DeliveryRepository deliveries;
    private final SecretCipher cipher;
    private final DestinationPolicy destinationPolicy;
    private final WebhookHttpClient http;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public DeliveryProcessor(JdbcClient jdbc, EndpointRepository endpoints, DeliveryRepository deliveries,
            SecretCipher cipher, DestinationPolicy destinationPolicy, WebhookHttpClient http, TransactionTemplate tx,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.cipher = cipher;
        this.destinationPolicy = destinationPolicy;
        this.http = http;
        this.tx = tx;
        this.objectMapper = objectMapper;
    }

    public void process(ClaimedDelivery claim) {
        HttpAttemptResult result = send(claim);
        record(claim, result);
    }

    private HttpAttemptResult send(ClaimedDelivery claim) {
        URI url;
        try {
            url = destinationPolicy.validateForSend(claim.requestUrl());
        } catch (DestinationPolicy.InvalidDestinationException e) {
            return HttpAttemptResult.failure(AttemptOutcome.DESTINATION_BLOCKED, e.getMessage(), 0);
        }
        String body = jdbc.sql("SELECT body FROM events WHERE id = :id")
                .param("id", claim.eventId())
                .query(String.class)
                .single();
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();
        String webhookId = claim.eventId().toString();

        var stored = endpoints.findSigningSecrets(claim.endpointId()).orElseThrow();
        List<String> secrets = new ArrayList<>(2);
        secrets.add(cipher.decrypt(stored.current(), claim.endpointId()));
        if (stored.previous() != null) {
            secrets.add(cipher.decrypt(stored.previous(), claim.endpointId()));
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("webhook-id", webhookId);
        headers.put("webhook-timestamp", Long.toString(timestamp));
        headers.put("webhook-signature", WebhookSigner.signatureHeader(secrets, webhookId, timestamp, bodyBytes));
        headers.put("X-Webhook-Delivery-Id", claim.deliveryId().toString());
        headers.put("X-Webhook-Attempt", Integer.toString(claim.attemptNumber()));
        return http.post(url, headers, bodyBytes);
    }

    private void record(ClaimedDelivery claim, HttpAttemptResult result) {
        boolean success = result.outcome() == AttemptOutcome.SUCCESS;
        String error = success ? null : errorSummary(result);
        AttemptRecord attempt = new AttemptRecord(
                result.outcome().name(),
                null,
                result.statusCode(),
                result.responseHeaders().isEmpty() ? null : objectMapper.writeValueAsString(result.responseHeaders()),
                result.responseBodyExcerpt(),
                result.errorMessage(),
                null,
                (int) Math.min(result.durationMs(), Integer.MAX_VALUE));

        boolean applied = Boolean.TRUE.equals(tx.execute(status -> {
            deliveries.finishAttempt(claim.attemptId(), success ? "SUCCEEDED" : "FAILED", attempt);
            if (success) {
                return deliveries.markDelivered(claim, result.statusCode());
            }
            // Stage 1 has no retry policy yet: every delivery has a budget of one attempt.
            DeadLetterReason reason = result.outcome() == AttemptOutcome.DESTINATION_BLOCKED
                    ? DeadLetterReason.DESTINATION_BLOCKED
                    : DeadLetterReason.RETRIES_EXHAUSTED;
            return deliveries.markDeadLettered(claim, reason, result.statusCode(), error);
        }));
        if (!applied) {
            log.warn("Discarded result for delivery {} attempt {}: lease no longer held (stale worker)",
                    claim.deliveryId(), claim.attemptNumber());
        } else {
            log.info("Delivery {} attempt {} -> {} ({} ms)", claim.deliveryId(), claim.attemptNumber(),
                    result.outcome(), result.durationMs());
        }
    }

    private static String errorSummary(HttpAttemptResult result) {
        if (result.statusCode() != null) {
            return "HTTP " + result.statusCode();
        }
        return result.errorMessage();
    }
}
