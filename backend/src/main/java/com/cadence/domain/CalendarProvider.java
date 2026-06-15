package com.cadence.domain;

import com.cadence.integration.UnsupportedProviderException;

import java.util.Locale;

/**
 * Supported calendar providers (F01.1). MVP = Google + Microsoft 365 only; anything else is rejected
 * (FR-019). The provider is part of the natural key {workspaceId,memberId,provider} and selects which
 * {@code OAuthGateway} performs the token exchange/refresh.
 */
public enum CalendarProvider {
    GOOGLE,
    MICROSOFT;

    /** Map a case-insensitive path segment to a provider; unknown -> UnsupportedProviderException (FR-019). */
    public static CalendarProvider fromPath(String value) {
        if (value == null) {
            throw new UnsupportedProviderException();
        }
        try {
            return CalendarProvider.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UnsupportedProviderException();
        }
    }

    /** Lowercase path segment (e.g. for the redirect URI). */
    public String path() {
        return name().toLowerCase(Locale.ROOT);
    }
}
