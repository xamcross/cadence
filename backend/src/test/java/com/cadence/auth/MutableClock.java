package com.cadence.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A test Clock whose instant can be set/advanced, for deterministic skew/lockout/renewal tests. */
public class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    public MutableClock(Instant start, ZoneId zone) {
        this.instant = start;
        this.zone = zone;
    }

    public void set(Instant newInstant) { this.instant = newInstant; }

    public void advance(Duration by) { this.instant = this.instant.plus(by); }

    @Override public ZoneId getZone() { return zone; }

    @Override public Clock withZone(ZoneId z) { return new MutableClock(instant, z); }

    @Override public Instant instant() { return instant; }
}
