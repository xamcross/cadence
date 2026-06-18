package com.cadence.integration;

import com.cadence.domain.AtsWriteBackType;

import java.time.Instant;

/**
 * A normalized outbound activity to write to the candidate's ATS timeline (F40, contract A). The
 * {@code note} carries ONLY a non-PII scheduling fact already known to the ATS (e.g. "Interview scheduled
 * for &lt;date&gt; via Cadence") — never candidate PII, never a scorecard/assessment (D5/FR-029).
 */
public record AtsActivity(AtsWriteBackType type, Instant occurredAt, String note) {
}
