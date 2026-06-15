package com.cadence.integration;

import com.cadence.domain.CalendarProvider;

/**
 * Provider-agnostic seam for the OAuth authorization-code + refresh + revoke calls (research D1).
 * Google/Microsoft implementations wrap the provider HTTP behind this interface so the service layer
 * never references a provider SDK or endpoint directly (constitution Dependency Policy). One bean per
 * provider; the service selects by {@link #id()}.
 */
public interface OAuthGateway {

    /** Which provider this gateway serves. */
    CalendarProvider id();

    /** Build the consent (authorization) URL — free/busy scope + offline params + PKCE challenge (research D7). */
    String authorizationUrl(String state, String codeChallenge, String redirectUri);

    /** Exchange an authorization code for tokens. Throws {@link OAuthTokenException} on failure. */
    TokenResponse exchangeCode(String code, String codeVerifier, String redirectUri);

    /** Refresh using the stored long-lived credential. Throws {@link OAuthTokenException} on failure. */
    TokenResponse refresh(String refreshToken);

    /** Best-effort revoke at the provider; failure is swallowed by the caller (FR-006). */
    void revoke(String token);

    /**
     * Normalised token-endpoint result. {@code refreshToken} is null when the provider did not re-issue
     * one (the caller MUST preserve the existing token — Security #8). {@code expiresInSeconds} is the
     * raw {@code expires_in}; the service computes the absolute expiry with its injected Clock.
     * {@code providerAccountId} is best-effort (null if the response carried no id_token / account claim).
     */
    record TokenResponse(String accessToken, String refreshToken, long expiresInSeconds,
                         String scope, String providerAccountId) {}
}
