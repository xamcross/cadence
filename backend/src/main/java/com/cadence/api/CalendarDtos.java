package com.cadence.api;

import java.time.Instant;
import java.util.List;

/**
 * Calendar connection DTOs (F01.1). NO response carries a token, code, or secret. The only
 * account-derived value is {@code connectedAccount} (the member's own connected email), returned to
 * the owning member with {@code Cache-Control: no-store} (Security #10).
 */
public final class CalendarDtos {

    private CalendarDtos() {}

    public record ConnectionRow(String provider, String status, String connectedAccount, Instant connectedAt) {}

    public record ConnectionList(List<ConnectionRow> connections) {}

    public record StartResponse(String authorizationUrl) {}

    /**
     * F10 self availability preview (D11). {@code provider} is null when not connected; {@code busy}
     * carries ONLY start/end instants — never any event title/attendee/location (FR-002).
     */
    public record AvailabilityPreviewResponse(String provider, String status,
                                              Instant windowStart, Instant windowEnd,
                                              List<com.cadence.domain.BusyInterval> busy) {}
}
