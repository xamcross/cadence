package com.cadence.domain;

/**
 * Lifecycle state of a {@link CalendarConnection} (F01.1). "Not connected" is the ABSENCE of a
 * document, never a stored value — so only the two live states exist here.
 */
public enum ConnectionStatus {
    /** Usable: a valid refresh token is held; the gate for {@code validAccessToken} is exactly this. */
    CONNECTED,
    /** The grant was permanently rejected (invalid_grant); the member must reconnect. */
    NEEDS_RECONNECTION
}
