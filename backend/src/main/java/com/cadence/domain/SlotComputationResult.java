package com.cadence.domain;

import java.util.List;

/**
 * The rule engine's output (F12, data-model §3): the compliant slots (empty when none comply, never an
 * error), whether the requested window was clamped to the configured maximum (FR-017), and the required
 * members who were unschedulable with a distinguishable reason (FR-014).
 */
public record SlotComputationResult(List<ComputedSlot> slots, boolean windowClamped,
                                    List<MemberUnschedulable> unschedulable) {}
