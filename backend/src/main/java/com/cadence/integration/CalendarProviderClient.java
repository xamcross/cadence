package com.cadence.integration;

import com.cadence.domain.BusyInterval;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.EventDetails;

import java.time.Instant;
import java.util.List;

/**
 * Forward domain abstraction (constitution Dependency Policy) for a calendar provider. F01.1 declared it
 * with {@link #validAccessToken} only; F10 widens it with the free/busy read + event CRUD that
 * {@code GoogleCalendarClient} implements (F11 adds {@code MicrosoftCalendarClient}). Business logic
 * depends on this interface, never on a provider SDK.
 *
 * <p>Renamed from the data-model §6 {@code CalendarProvider} to {@code CalendarProviderClient} so the
 * interface does not clash with the {@link CalendarProvider} enum.
 */
public interface CalendarProviderClient {

    /** Which provider this implementation serves. */
    CalendarProvider id();

    /**
     * Returns a currently-valid access token for the member, refreshing transparently if expired (F01.1).
     * Throws {@link CalendarReconnectRequiredException} / {@link CalendarProviderTransientException} /
     * {@link CalendarNotConnectedException}. Never returns an expired token.
     */
    String validAccessToken(String workspaceId, String memberId);

    /**
     * Busy intervals for ONE member over {@code [windowStart, windowEnd)} via the provider's free/busy
     * endpoint ONLY (research D2 — no event content is ever received). Throws
     * {@link CalendarReconnectRequiredException} (revoked/insufficient-scope), {@link CalendarApiException}
     * (transient after bounded retry), or {@link CalendarNotConnectedException}.
     */
    List<BusyInterval> queryFreeBusy(String workspaceId, String memberId, Instant windowStart, Instant windowEnd);

    /**
     * Idempotent create of a Cadence interview event on the member's calendar; RETURNS the
     * provider-assigned event id. For Google the id is a deterministic client-supplied id; for Microsoft
     * Graph it is the SERVER-assigned id read back from the create response (F11 D5). The caller MUST
     * persist the returned id and use it to address later update/delete. A retried create for the same
     * {@code (bookingRef, memberId)} does NOT produce a duplicate (Google: deterministic id + 409-success;
     * Microsoft: {@code transactionId} dedup), backed by the caller's unique-index claim.
     */
    String createEvent(String workspaceId, String bookingRef, String memberId, EventDetails details);

    /**
     * In-place update (time/title/location) of the event addressed by its STORED {@code providerEventId}.
     * Idempotent (a provider "already gone" 404/410 is treated as success — FR-011).
     */
    void updateEvent(String workspaceId, String memberId, String providerEventId, EventDetails details);

    /**
     * Idempotent delete of the event addressed by its STORED {@code providerEventId}; a provider "already
     * gone" (404/410) is treated as success (FR-011).
     */
    void deleteEvent(String workspaceId, String memberId, String providerEventId);
}
