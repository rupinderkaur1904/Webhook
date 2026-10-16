package dev.webhooks.simulator;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Receiver-side Standard Webhooks verification, written independently of the platform's signer.
 * Mirrors what a real receiver should do: check the timestamp tolerance, recompute the HMAC over
 * {@code id.timestamp.body}, and compare in constant time against every {@code v1,} signature.
 */
public final class SignatureVerifier {

    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    public enum Result { VALID, INVALID_SIGNATURE, TIMESTAMP_OUT_OF_TOLERANCE, MISSING_HEADERS }

    private SignatureVerifier() {}

    public static Result verify(String secret, String id, String timestamp, String signatureHeader, byte[] body,
            Instant now, Duration tolerance) {
        if (id == null || timestamp == null || signatureHeader == null) {
            return Result.MISSING_HEADERS;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return Result.MISSING_HEADERS;
        }
        if (Math.abs(now.getEpochSecond() - ts) > tolerance.toSeconds()) {
            return Result.TIMESTAMP_OUT_OF_TOLERANCE;
        }
        byte[] expected = hmac(secret, id + "." + timestamp + ".", body);
        for (String part : signatureHeader.trim().split(" +")) {
            if (!part.startsWith("v1,")) {
                continue;
            }
            byte[] provided;
            try {
                provided = Base64.getDecoder().decode(part.substring(3));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (MessageDigest.isEqual(expected, provided)) {
                return Result.VALID;
            }
        }
        return Result.INVALID_SIGNATURE;
    }

    /** True if {@code secret} ({@code whsec_} optional) decodes to a non-empty base64 HMAC key. */
    public static boolean isValidSecret(String secret) {
        if (secret == null) {
            return false;
        }
        String encoded = secret.startsWith("whsec_") ? secret.substring(6) : secret;
        try {
            return Base64.getDecoder().decode(encoded).length > 0;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static byte[] hmac(String secret, String prefix, byte[] body) {
        String encoded = secret.startsWith("whsec_") ? secret.substring(6) : secret;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(encoded), "HmacSHA256"));
            mac.update(prefix.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
