package com.cadence.domain;

import java.time.Instant;

/**
 * One occupied time range for one member (F10): absolute instants only (research D5) — never any event
 * content (title/attendee/location). The provider-neutral free/busy unit the rule engine (F12) and the
 * booking flow (F13) consume; F11 normalises Microsoft Graph into the same shape.
 */
public record BusyInterval(Instant start, Instant end) {}
