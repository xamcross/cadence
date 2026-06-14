package com.cadence.security;

import com.cadence.config.AuthProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Keyed HMAC for invitation/reset link tokens and audit source-IP values (research D4 / SEC-2 /
 * SEC-6). Peppering means a DB-only leak yields nothing matchable, and an IP hash is not
 * brute-force reversible across the small IPv4 space.
 */
@Component
public class TokenHasher {

    private final byte[] tokenPepper;
    private final byte[] ipPepper;

    public TokenHasher(AuthProperties props) {
        this.tokenPepper = props.getCrypto().getTokenPepper().getBytes(StandardCharsets.UTF_8);
        this.ipPepper = props.getCrypto().getIpPepper().getBytes(StandardCharsets.UTF_8);
    }

    /** HMAC of a raw link token (invitation / password reset). */
    public String hashToken(String rawToken) {
        return rawToken == null ? null : PiiCrypto.hmacBase64(rawToken, tokenPepper);
    }

    /** HMAC of a source IP for non-reversible audit logging. */
    public String hashIp(String ip) {
        return ip == null ? null : PiiCrypto.hmacBase64(ip, ipPepper);
    }
}
