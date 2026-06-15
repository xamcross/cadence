package com.cadence.domain;

import java.time.LocalDate;

/**
 * The internal input to {@code RuleEngine.compute} (F12, contract §E). The range is civil dates in the
 * applicable zone; the engine resolves it to a clamped absolute window. Carries NO member list — the
 * member set is read from the persisted, validated template (the D8 compute-path isolation control).
 */
public record SlotComputationRequest(String workspaceId, String templateId,
                                     LocalDate rangeStart, LocalDate rangeEnd) {}
