package com.cadence.domain;

/**
 * F31 server-computed communication-health classification for a candidate (data-model section 2).
 * Never persisted — derived on read from {@code lastContactAt} vs the workspace silence window.
 */
public enum SlaState {
    /** Within SLA. */
    GREEN,
    /** Within the nearing-breach margin (not yet breached). */
    AMBER,
    /** Breached — last meaningful activity older than the silence window. */
    RED
}
