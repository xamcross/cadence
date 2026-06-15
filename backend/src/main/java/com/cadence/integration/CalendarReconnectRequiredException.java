package com.cadence.integration;

/**
 * The grant is permanently invalid (the refresh failed with {@code invalid_grant}); the connection has
 * been flipped to NEEDS_RECONNECTION and the member must reconnect (FR-015). No valid credential is
 * available — callers (F10/F11) must surface a reconnect prompt, not retry.
 */
public class CalendarReconnectRequiredException extends RuntimeException {
    public CalendarReconnectRequiredException() {
        super("calendar reconnection required");
    }
}
