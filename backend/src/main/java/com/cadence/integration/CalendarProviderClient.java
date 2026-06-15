package com.cadence.integration;

import com.cadence.domain.CalendarProvider;

/**
 * Forward domain abstraction (constitution Dependency Policy) for a calendar provider. F01.1 declares
 * it and ships the token-store implementation behind it via {@code CalendarTokenService}; the concrete
 * Google/Microsoft adapters (free/busy reads, event CRUD) land in F10/F11 and widen it.
 *
 * <p>Renamed from the data-model §6 {@code CalendarProvider} to {@code CalendarProviderClient} so the
 * interface does not clash with the {@link CalendarProvider} enum (tasks T014, backend-review #2).
 */
public interface CalendarProviderClient {

    /** Which provider this implementation serves. */
    CalendarProvider id();

    /**
     * Returns a currently-valid access token for the member, refreshing transparently if expired
     * (research D5/D6). Throws {@link CalendarReconnectRequiredException} if the grant is permanently
     * invalid, {@link CalendarProviderTransientException} after bounded retry on a transient failure,
     * and {@link CalendarNotConnectedException} if the member has no connection. Never returns an
     * expired token.
     */
    String validAccessToken(String workspaceId, String memberId);
}
