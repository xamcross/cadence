package com.cadence.security;

import com.cadence.config.AuthProperties;
import com.cadence.domain.Role;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Signs and verifies the self-issued session JWT (research D1). HS256 only — the verifier is
 * pinned to HS256 and rejects {@code alg:none}/alg-confusion (SC-012). A {@code kid} header selects
 * the current vs previous signing key so a key can be rotated with an overlap window (SEC-3).
 * Expiry is checked with ±clock-skew here (the crypto layer); the authoritative absolute/idle/
 * revoked checks happen against the session registry at exact now (SEC-11).
 */
@Component
public class JwtSupport {

    public static final String KID_CURRENT = "k1";
    public static final String KID_PREVIOUS = "k0";

    private final byte[] currentKey;
    private final byte[] previousKey; // nullable
    private final Duration skew;
    private final Clock clock;

    public JwtSupport(AuthProperties props, Clock clock) {
        this.currentKey = deriveKey(props.getSession().getSecret());
        String prev = props.getSession().getPreviousSecret();
        this.previousKey = (prev == null || prev.isBlank()) ? null : deriveKey(prev);
        this.skew = props.getSession().getClockSkew();
        this.clock = clock;
    }

    public String issue(String jti, String memberId, String workspaceId, Role role, Instant expiresAt) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .jwtID(jti)
                .subject(memberId)
                .claim("wid", workspaceId)
                .claim("role", role.name())
                .issueTime(Date.from(Instant.now(clock)))
                .expirationTime(Date.from(expiresAt))
                .build();
            SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KID_CURRENT).build(), claims);
            jwt.sign(new MACSigner(currentKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("JWT signing failed", e);
        }
    }

    public Optional<ParsedToken> verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            // Pin algorithm — reject none/alg-confusion (SC-012).
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
                return Optional.empty();
            }
            if (!verifyWithKeys(jwt)) {
                return Optional.empty();
            }
            Date exp = jwt.getJWTClaimsSet().getExpirationTime();
            if (exp == null) {
                return Optional.empty();
            }
            // Skew applies ONLY to this cryptographic exp check (SEC-11).
            if (Instant.now(clock).minus(skew).isAfter(exp.toInstant())) {
                return Optional.empty();
            }
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            return Optional.of(new ParsedToken(
                c.getJWTID(), c.getSubject(), (String) c.getClaim("wid"),
                Role.valueOf((String) c.getClaim("role"))));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Accept a signature from the current OR previous key (a 2-key validity set during rotation,
     * JWKS-style). Tokens are always issued with the current key; after rotation, still-live tokens
     * verify against the previous key until they expire. The kid is informational only — an attacker
     * would still have to forge an HMAC under one of two secret keys, which is infeasible (SEC-1).
     */
    private boolean verifyWithKeys(SignedJWT jwt) throws Exception {
        if (jwt.verify(new MACVerifier(currentKey))) {
            return true;
        }
        return previousKey != null && jwt.verify(new MACVerifier(previousKey));
    }

    private static byte[] deriveKey(String secret) {
        try {
            // HS256 needs a >=256-bit key; SHA-256 the configured secret to a fixed 32 bytes.
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Key derivation failed", e);
        }
    }

    public record ParsedToken(String jti, String memberId, String workspaceId, Role role) {}
}
