package com.cadence.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Deterministic Google Calendar event id (F10, research D6). Google ids must match {@code [a-v0-9]{5,1024}}
 * (RFC-4648 base32hex). Deriving the id from {@code (bookingRef, memberId)} makes a retried insert
 * idempotent: re-inserting the same id returns 409, which the client treats as success. The inputs are
 * LENGTH-PREFIXED before hashing so {@code ("a","bc")} and {@code ("ab","c")} cannot collide
 * (plan-review Backend MINOR). Inputs are internal opaque ids (no PII oracle).
 */
public final class GoogleEventId {

    private static final char[] BASE32HEX = "0123456789abcdefghijklmnopqrstuv".toCharArray();

    private GoogleEventId() {}

    public static String of(String bookingRef, String memberId) {
        String joined = bookingRef.length() + ":" + bookingRef + ":" + memberId.length() + ":" + memberId;
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        return base32hex(digest); // 256 bits -> 52 chars, within Google's 5..1024
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
