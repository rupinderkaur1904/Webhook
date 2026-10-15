package dev.webhooks.platform.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Random token generation and hashing for API keys, signing secrets, and request fingerprints. */
public final class Tokens {

    public static final String API_KEY_PREFIX = "whk_";
    public static final String SIGNING_SECRET_PREFIX = "whsec_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {}

    /** {@code whk_} + 32 random bytes, base64url without padding. */
    public static String newApiKey() {
        return API_KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
    }

    /** {@code whsec_} + 32 random bytes, standard base64 (Standard Webhooks secret format). */
    public static String newSigningSecret() {
        return SIGNING_SECRET_PREFIX + Base64.getEncoder().encodeToString(randomBytes(32));
    }

    public static byte[] sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
