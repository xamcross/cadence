package com.cadence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds the {@code cadence.noshow.*} block (F23). Auto-registers via the existing
 * {@code @ConfigurationPropertiesScan}. No secrets.
 *
 * <p>The cascade fires three per-booking stages relative to the interview start: a confirmation request at
 * {@code confirmationLeadTime} before start, an unconfirmed escalation at {@code escalationDeadline} before
 * start, and a no-show stamp at start. {@code confirmationLeadTime}/{@code escalationDeadline} are the GLOBAL
 * defaults — a per-workspace override on {@code WorkspaceConfig} wins (research D7).
 *
 * <p><b>{@code cascadeQueryBound} invariant (research D2/D7):</b> the indexed sweep selects
 * {@code bookedStartAt <= now + cascadeQueryBound}, then Java-filters per-workspace offsets. A workspace
 * {@code confirmationLeadTime} MUST be {@code <= cascadeQueryBound} (validated at config save) or the scan
 * would miss it.
 */
@ConfigurationProperties(prefix = "cadence.noshow")
public class NoShowProperties {

    /** Global default: confirmation request this far before the interview start (FR-001/FR-015). */
    private Duration confirmationLeadTime = Duration.ofHours(24);

    /** Global default: escalate an unconfirmed interview this far before start (FR-010/FR-015). */
    private Duration escalationDeadline = Duration.ofHours(2);

    /** The cascade @Scheduled fixed delay. */
    private long cascadeIntervalMs = 60_000;

    /** Upper bound for the indexed cascade scan ({@code bookedStartAt <= now + this}); >= any workspace lead time. */
    private Duration cascadeQueryBound = Duration.ofHours(72);

    /** Batch cap on each cascade stage scan so a backlog cannot load an unbounded result set per tick. */
    private int cascadeSweepBatchLimit = 200;

    public Duration getConfirmationLeadTime() { return confirmationLeadTime; }
    public void setConfirmationLeadTime(Duration confirmationLeadTime) { this.confirmationLeadTime = confirmationLeadTime; }

    public Duration getEscalationDeadline() { return escalationDeadline; }
    public void setEscalationDeadline(Duration escalationDeadline) { this.escalationDeadline = escalationDeadline; }

    public long getCascadeIntervalMs() { return cascadeIntervalMs; }
    public void setCascadeIntervalMs(long cascadeIntervalMs) { this.cascadeIntervalMs = cascadeIntervalMs; }

    public Duration getCascadeQueryBound() { return cascadeQueryBound; }
    public void setCascadeQueryBound(Duration cascadeQueryBound) { this.cascadeQueryBound = cascadeQueryBound; }

    public int getCascadeSweepBatchLimit() { return cascadeSweepBatchLimit; }
    public void setCascadeSweepBatchLimit(int cascadeSweepBatchLimit) { this.cascadeSweepBatchLimit = cascadeSweepBatchLimit; }
}
