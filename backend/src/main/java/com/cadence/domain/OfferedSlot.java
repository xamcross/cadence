package com.cadence.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One slot in a {@link SchedulingRequest}'s offered-slot snapshot (F13, data-model §2). Absolute
 * start/end + display zone, the required member ids, and per-pool the candidate member ids that
 * qualified at compute time ({@code poolCandidates.get(i)} = candidates for the template's pool {@code i}).
 * No PII. The candidate-facing projection exposes ONLY {@code slotId/start/end/zoneId} (FR-011) — the
 * member-id fields are server-side only (re-validation / pool re-selection at confirm).
 */
public class OfferedSlot {

    private String slotId;
    private Instant start;
    private Instant end;
    private String zoneId;
    private List<String> requiredMemberIds = new ArrayList<>();
    /** Indexed by the template's pool position; each entry is that pool's qualifying candidate ids. */
    private List<List<String>> poolCandidates = new ArrayList<>();

    public OfferedSlot() {}

    public String getSlotId() { return slotId; }
    public void setSlotId(String slotId) { this.slotId = slotId; }

    public Instant getStart() { return start; }
    public void setStart(Instant start) { this.start = start; }

    public Instant getEnd() { return end; }
    public void setEnd(Instant end) { this.end = end; }

    public String getZoneId() { return zoneId; }
    public void setZoneId(String zoneId) { this.zoneId = zoneId; }

    public List<String> getRequiredMemberIds() { return requiredMemberIds; }
    public void setRequiredMemberIds(List<String> requiredMemberIds) { this.requiredMemberIds = requiredMemberIds; }

    public List<List<String>> getPoolCandidates() { return poolCandidates; }
    public void setPoolCandidates(List<List<String>> poolCandidates) { this.poolCandidates = poolCandidates; }
}
