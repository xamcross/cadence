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
}
