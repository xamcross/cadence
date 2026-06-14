package com.cadence.security;

import java.security.SecureRandom;
import java.util.Base64;

/** Generates 256-bit URL-safe random link tokens (FR-030). */
public final class SecureTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecureTokens() {}

    public static String newToken() {
        byte[] bytes = new byte[32]; // 256 bits
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
