package com.cadence.domain;

/**
 * F70 interest-request lifecycle (data-model section "Status lifecycle", FR-013). {@code NEW}/{@code REVIEWED}
 * are OPEN (carry {@code openEmailHash}); {@code INVITED}/{@code DISMISSED} are TERMINAL (drop it). Safe to log
 * via {@code .name()} only (the F01.1 logstash {@code kv} footgun).
 */
public enum InterestRequestStatus {
    /** Newly submitted, awaiting triage. Open. */
    NEW,
    /** An admin marked it reviewed; stays in the queue, dropped from the default "needs triage" filter. Open. */
    REVIEWED,
    /** Converted to an invitation (terminal; openEmailHash unset; invitationId set). */
    INVITED,
    /** Dismissed by an admin (terminal; openEmailHash unset). */
    DISMISSED
}
