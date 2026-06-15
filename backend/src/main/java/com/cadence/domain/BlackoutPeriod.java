package com.cadence.domain;

import java.time.Instant;

/**
 * A date/time range during which a template offers no slots (F12, FR-013). Absolute instants only
 * (no naive local time), with NO free-text label (a free-text label would be a PII vector — D10).
 * {@code end} must be strictly after {@code start} (FR-002).
 */
public class BlackoutPeriod {

    private Instant start;
    private Instant end;

    public BlackoutPeriod() {}

    public BlackoutPeriod(Instant start, Instant end) {
        this.start = start;
        this.end = end;
    }

    public Instant getStart() { return start; }
    public void setStart(Instant start) { this.start = start; }

    public Instant getEnd() { return end; }
    public void setEnd(Instant end) { this.end = end; }
}
