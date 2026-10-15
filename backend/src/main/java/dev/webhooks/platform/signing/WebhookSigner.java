package dev.webhooks.platform.signing;

import dev.webhooks.platform.crypto.Tokens;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Standard Webhooks signatures (https://www.standardwebhooks.com/).
 *
 * <p>Signed bytes: UTF-8 of {@code "{webhook-id}.{webhook-timestamp}."} followed by the exact
 * request body bytes. Key: the base64-decoded part of the secret after {@code whsec_}. Header value:
 * {@code v1,<base64 HMAC-SHA256>}; several signatures (e.g. during secret rotation) are
 * space-separated.
 */
public final class WebhookSigner {

    private WebhookSigner() {}

    public static String sign(String secret, String webhookId, long timestampSeconds, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey(secret), "HmacSHA256"));
            mac.update((webhookId + "." + timestampSeconds + ".").getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public static String signatureHeader(List<String> secrets, String webhookId, long timestampSeconds, byte[] body) {
        return secrets.stream()
                .map(secret -> sign(secret, webhookId, timestampSeconds, body))
                .collect(Collectors.joining(" "));
    }

    static byte[] secretKey(String secret) {
        String encoded = secret.startsWith(Tokens.SIGNING_SECRET_PREFIX)
                ? secret.substring(Tokens.SIGNING_SECRET_PREFIX.length())
                : secret;
        return Base64.getDecoder().decode(encoded);
    }
}
