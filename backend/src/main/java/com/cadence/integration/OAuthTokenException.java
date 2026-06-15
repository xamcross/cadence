package com.cadence.integration;

/**
 * Low-level failure of a provider token-endpoint call (exchange or refresh). Carries the standard OAuth
 * {@code error} code (e.g. {@code invalid_grant}) when the provider returned an error body, and/or the
 * HTTP status, so {@code OAuthFailureClassifier} can classify it PERMANENT / TRANSIENT / FATAL
 * (research D6). Never carries a token value (never logged with a secret).
 */
public class OAuthTokenException extends RuntimeException {

    private final String oauthError; // e.g. "invalid_grant"; null if none
    private final Integer httpStatus; // e.g. 429, 503; null for a network error

    public OAuthTokenException(String oauthError, Integer httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.oauthError = oauthError;
        this.httpStatus = httpStatus;
    }

    public String getOauthError() { return oauthError; }
    public Integer getHttpStatus() { return httpStatus; }
}
