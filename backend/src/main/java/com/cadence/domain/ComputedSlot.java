package com.cadence.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One offerable interview time produced by the rule engine (F12, data-model §3). Absolute start/end
 * instants + the applicable zone; the required members and, per pool (by pool index), the members who
 * satisfy that pool's quorum (FR-010) so F13 can finalise the concrete panel independently per pool.
 * Provider-agnostic; holds NO calendar event content. Ordering is deterministic: slots ascending by
 * {@code start}; member-id lists ascending (FR-016).
 */
public record ComputedSlot(Instant start, Instant end, String zoneId,
                           List<String> requiredMemberIds,
                           Map<Integer, List<String>> qualifyingByPoolIndex) {}
