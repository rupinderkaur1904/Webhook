package dev.webhooks.platform.crypto;

import dev.webhooks.platform.config.WebhookProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Encrypts endpoint signing secrets at rest with AES-256-GCM.
 *
 * <p>Signing secrets cannot be hashed because the platform must compute HMACs with them. The
 * ciphertext is bound to its endpoint id (GCM associated data), so a ciphertext copied onto a
 * different endpoint row fails to decrypt. Layout: {@code version(1) | iv(12) | ciphertext+tag}.
 */
@Component
public class SecretCipher {

    private static final byte VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(WebhookProperties properties) {
        String encoded = properties.secrets().encryptionKey();
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException(
                    "webhooks.secrets.encryption-key (SECRET_ENCRYPTION_KEY) must be set to a base64-encoded 32-byte key");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("webhooks.secrets.encryption-key is not valid base64");
        }
        if (raw.length != 32) {
            throw new IllegalStateException("webhooks.secrets.encryption-key must decode to exactly 32 bytes");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public byte[] encrypt(String plaintext, UUID endpointId) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(endpointId));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + IV_BYTES + ciphertext.length).put(VERSION).put(iv).put(ciphertext).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Secret encryption failed", e);
        }
    }

    public String decrypt(byte[] stored, UUID endpointId) {
        if (stored.length < 1 + IV_BYTES + TAG_BITS / 8 || stored[0] != VERSION) {
            throw new IllegalStateException("Unsupported secret ciphertext format");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 1, IV_BYTES));
            cipher.updateAAD(aad(endpointId));
            byte[] plaintext = cipher.doFinal(stored, 1 + IV_BYTES, stored.length - 1 - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Secret decryption failed", e);
        }
    }

    private static byte[] aad(UUID endpointId) {
        return ByteBuffer.allocate(16)
                .putLong(endpointId.getMostSignificantBits())
                .putLong(endpointId.getLeastSignificantBits())
                .array();
    }
}
