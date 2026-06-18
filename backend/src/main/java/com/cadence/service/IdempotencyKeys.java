package com.cadence.service;

import com.cadence.domain.AtsWriteBackType;
import com.cadence.domain.EmailMessageType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Derives the F22 dispatch idempotency key (research D5/D9) — {@code sha256(workspaceId | candidateId |
 * messageType | scheduledForEpochMillis)} rendered base32hex. Inputs are LENGTH-PREFIXED before hashing so
 * adjacent fields cannot collide by concatenation (the F10 {@code GoogleEventId} precedent). All inputs are
 * internal opaque ids / a numeric instant — no PII. The key is the durable exactly-once guarantee (it is
 * the unique {workspaceId,idempotencyKey} index value) and doubles as the SMTP {@code Message-ID} hint.
 */
public final class IdempotencyKeys {

    private static final char[] BASE32HEX = "0123456789abcdefghijklmnopqrstuv".toCharArray();

    private IdempotencyKeys() {}

    public static String dispatchKey(String workspaceId, String candidateId, EmailMessageType type,
                                     long scheduledForEpochMillis) {
        String typeName = type.name();
        String millis = Long.toString(scheduledForEpochMillis);
        String joined = workspaceId.length() + ":" + workspaceId
            + ":" + candidateId.length() + ":" + candidateId
            + ":" + typeName.length() + ":" + typeName
            + ":" + millis.length() + ":" + millis;
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        return base32hex(digest);
    }

    /**
     * F40 ATS write-back idempotency key — {@code sha256(workspaceId | candidateId | type | eventAtEpochMillis)}
     * base32hex, LENGTH-PREFIXED (the same anti-collision discipline). The key is derived from the deterministic
     * originating event instant ({@code eventAt}), NOT enqueue time, so a re-enqueue of the same logical event
     * collides on the unique {workspaceId,idempotencyKey} index (exactly-once). No PII — ids + a numeric instant.
     */
    public static String atsWriteBackKey(String workspaceId, String candidateId, AtsWriteBackType type,
                                         long eventAtEpochMillis) {
        String typeName = type.name();
        String millis = Long.toString(eventAtEpochMillis);
        String joined = workspaceId.length() + ":" + workspaceId
            + ":" + candidateId.length() + ":" + candidateId
            + ":" + typeName.length() + ":" + typeName
            + ":" + millis.length() + ":" + millis;
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        return base32hex(digest);
    }

    private static String base32hex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(BASE32HEX[(buffer >> bits) & 0x1F]);
            }
        }
        if (bits > 0) {
            sb.append(BASE32HEX[(buffer << (5 - bits)) & 0x1F]);
        }
        return sb.toString();
    }
}
