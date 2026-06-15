package com.cadence.domain;

import java.time.ZoneId;

/**
 * One interview participant for a panel booking (F10): the internal member id whose calendar will hold
 * the event, plus the IANA zone used to render the event wall-clock for that attendee (research D5). No PII.
 */
public record Participant(String memberId, ZoneId timeZone) {}
