package com.cadence.security;

import com.cadence.config.AuthProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * Application-level AES-256-GCM encryption for member PII at rest, plus a keyed HMAC for the
 * query-able {@code emailHash} (research D12 / SEC-1). A DB reader sees only ciphertext, so SC-011
 * holds. Keys/peppers come from Fly secrets via {@link AuthProperties.Crypto}.
 */
@Component
public class PiiCrypto {

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKeySpec aesKey;
    private final byte[] emailPepper;
    private final SecureRandom random = new SecureRandom();

    public PiiCrypto(AuthProperties props) {
        this.aesKey = new SecretKeySpec(sha256(props.getCrypto().getPiiKey()), "AES");
        this.emailPepper = props.getCrypto().getPiiPepper().getBytes(StandardCharsets.UTF_8);
    }

    /** Encrypt plaintext to base64(iv || ciphertext+tag). Null-safe. */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("PII encryption failed", e);
        }
    }

    /** Decrypt base64(iv || ciphertext+tag). Null-safe. */
    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            byte[] in = Base64.getDecoder().decode(stored);
            byte[] iv = new byte[GCM_IV_BYTES];
            System.arraycopy(in, 0, iv, 0, GCM_IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] pt = cipher.doFinal(in, GCM_IV_BYTES, in.length - GCM_IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("PII decryption failed", e);
        }
    }

    /** Deterministic keyed hash of an email for the unique index + equality lookup. */
    public String emailHash(String email) {
        if (email == null) {
            return null;
        }
        return hmacBase64(email.trim().toLowerCase(Locale.ROOT), emailPepper);
    }

    static String hmacBase64(String value, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] out = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
