package com.cadence.domain;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Caller-supplied details for a Cadence interview event (F10). {@code title}/{@code location} are
 * recruiter free-text and are treated as candidate PII: they are forwarded to the provider on the
 * create/update call but are NEVER persisted by F10 and NEVER logged. The {@code toString()} below is
 * deliberately REDACTING (omits title/location) so an exception that captures an EventDetails cannot leak
 * them (plan-review M3; the F03 secret-toString discipline). {@code timeZone} is the IANA zone sent to the
 * provider for DST-correct rendering (research D5).
 */
public record EventDetails(String title, String location, Instant startAt, Instant endAt, ZoneId timeZone) {

    /** Redacting — NEVER include title/location (PII). */
    @Override
    public String toString() {
        return "EventDetails{startAt=" + startAt + ", endAt=" + endAt + ", timeZone=" + timeZone
            + ", title=[REDACTED], location=[REDACTED]}";
    }
}
