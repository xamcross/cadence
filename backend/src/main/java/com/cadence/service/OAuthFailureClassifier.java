package com.cadence.service;

import com.cadence.integration.OAuthTokenException;
import org.springframework.stereotype.Component;

/**
 * Classifies a token-endpoint failure so {@code CalendarTokenService} can decide whether to flip the
 * connection to NEEDS_RECONNECTION or retry (research D6).
 *
 * <ul>
 *   <li>{@code invalid_grant} (revoked/expired refresh token) -> PERMANENT (no retry; reconnect).
 *   <li>HTTP 429 / any 5xx / a network error (no status) -> TRANSIENT (bounded retry).
 *   <li>any other 4xx OAuth error -> FATAL (a config/programming error; do not loop).
 * </ul>
 */
@Component
public class OAuthFailureClassifier {

    public enum Classification { PERMANENT, TRANSIENT, FATAL }

    public Classification classify(OAuthTokenException e) {
        if ("invalid_grant".equals(e.getOauthError())) {
            return Classification.PERMANENT;
        }
        Integer status = e.getHttpStatus();
        if (status == null) {
            return Classification.TRANSIENT; // network/connect error
        }
        if (status == 429 || status >= 500) {
            return Classification.TRANSIENT;
        }
        return Classification.FATAL;
    }
}
