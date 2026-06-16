package com.cadence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds the {@code cadence.scheduling.*} block (F13). Auto-registers via the existing
 * {@code @ConfigurationPropertiesScan}. No secrets.
 *
 * <p><b>Reaper-threshold invariant (research D6):</b> {@code reaperThreshold > (perCallReadTimeout +
 * maxBackoff) * maxPanelSize} — a confirm fans out a panel calendar create, so the worst-case in-flight
 * duration scales with the panel size; the stale-{@code BOOKING} reaper must never release a live confirm.
 */
@ConfigurationProperties(prefix = "cadence.scheduling")
public class SchedulingProperties {

    /** Scheduling-link time-to-live from send (default 72h, FR-008). */
    private Duration tokenTtl = Duration.ofHours(72);

    /** Default search window (days) when the recruiter does not narrow it. */
    private int searchWindowDays = 10;

    /** A request stuck in BOOKING older than this is released back to PENDING_SELECTION (FR-017). */
    private Duration reaperThreshold = Duration.ofMinutes(10);

    /** Batch cap on each reaper scan so a backlog cannot load an unbounded result set per tick. */
    private int reaperSweepBatchLimit = 200;

    /** Candidate-endpoint rate limit per source IP per minute (FR-010); 429 on breach. */
    private int rateLimitPerMinute = 10;

    /** SPA path for the candidate scheduling page; appended to the F01 {@code spaBaseUrl}. */
    private String spaScheduleBasePath = "/schedule";

    public Duration getTokenTtl() { return tokenTtl; }
    public void setTokenTtl(Duration tokenTtl) { this.tokenTtl = tokenTtl; }

    public int getSearchWindowDays() { return searchWindowDays; }
    public void setSearchWindowDays(int searchWindowDays) { this.searchWindowDays = searchWindowDays; }

    public Duration getReaperThreshold() { return reaperThreshold; }
    public void setReaperThreshold(Duration reaperThreshold) { this.reaperThreshold = reaperThreshold; }

    public int getReaperSweepBatchLimit() { return reaperSweepBatchLimit; }
    public void setReaperSweepBatchLimit(int reaperSweepBatchLimit) { this.reaperSweepBatchLimit = reaperSweepBatchLimit; }

    public int getRateLimitPerMinute() { return rateLimitPerMinute; }
    public void setRateLimitPerMinute(int rateLimitPerMinute) { this.rateLimitPerMinute = rateLimitPerMinute; }

    public String getSpaScheduleBasePath() { return spaScheduleBasePath; }
    public void setSpaScheduleBasePath(String spaScheduleBasePath) { this.spaScheduleBasePath = spaScheduleBasePath; }
}
