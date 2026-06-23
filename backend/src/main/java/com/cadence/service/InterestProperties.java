package com.cadence.service;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * F70 Join / Express-Interest global knobs ({@code cadence.interest.*}).
 *
 * <p>{@code defaultWorkspaceId} (FR-019): the owning workspace is resolved SERVER-SIDE from this value, NEVER
 * from submitter input. {@code retentionFallbackDays} (data-model / FR-021): the {@code WorkspaceConfig
 * .retentionPeriodDays} is a primitive {@code int} with default {@code 0}; a {@code 0}/unset value means "no
 * explicit policy" and falls back to this default (NOT immediate delete). The two limiter caps + the
 * per-workspace DB ceiling are the layered flood defence (R6): the per-source hashed-IP limiter is best-effort
 * (layer 1), the per-workspace DB-count ceiling is the durable guard (layer 2). {@code minFillMillis} is the
 * bot-heuristic minimum form-fill time.
 */
@Component
@ConfigurationProperties(prefix = "cadence.interest")
public class InterestProperties {

    /** The owning workspace for every public submission (FR-019). Resolved server-side; never from input. */
    private String defaultWorkspaceId = "cadence";

    /** Retention fallback when a workspace leaves {@code retentionPeriodDays} at {@code 0}/unset (FR-021). */
    private int retentionFallbackDays = 180;

    /** Per-source (hashed-IP) cap within {@code ipWindow} — best-effort layer-1 limiter (R6). */
    private int maxPerIpPerWindow = 5;

    /** The fixed window for the per-source limiter. */
    private Duration ipWindow = Duration.ofMinutes(10);

    /** Per-workspace DB-count ceiling within {@code workspaceWindow} — the durable layer-2 guard (R6). */
    private int maxPerWorkspacePerWindow = 100;

    /** The fixed window for the per-workspace DB-count ceiling. */
    private Duration workspaceWindow = Duration.ofHours(1);

    /** Bot heuristic: a form filled in under this many millis is treated as a bot (neutral accept, no row). */
    private long minFillMillis = 1500;

    @PostConstruct
    void validate() {
        if (defaultWorkspaceId == null || defaultWorkspaceId.isBlank()) {
            throw new IllegalStateException("cadence.interest.default-workspace-id must be set");
        }
        if (retentionFallbackDays < 1) {
            throw new IllegalStateException("cadence.interest.retention-fallback-days must be positive");
        }
        if (maxPerIpPerWindow < 1 || maxPerWorkspacePerWindow < 1) {
            throw new IllegalStateException("cadence.interest per-window caps must be positive");
        }
        requirePositive(ipWindow, "ipWindow");
        requirePositive(workspaceWindow, "workspaceWindow");
        if (minFillMillis < 0) {
            throw new IllegalStateException("cadence.interest.min-fill-millis must be non-negative");
        }
    }

    private static void requirePositive(Duration d, String name) {
        if (d == null || d.isZero() || d.isNegative()) {
            throw new IllegalStateException("cadence.interest." + name + " must be a positive duration");
        }
    }

    public String getDefaultWorkspaceId() { return defaultWorkspaceId; }
    public void setDefaultWorkspaceId(String defaultWorkspaceId) { this.defaultWorkspaceId = defaultWorkspaceId; }

    public int getRetentionFallbackDays() { return retentionFallbackDays; }
    public void setRetentionFallbackDays(int retentionFallbackDays) { this.retentionFallbackDays = retentionFallbackDays; }

    public int getMaxPerIpPerWindow() { return maxPerIpPerWindow; }
    public void setMaxPerIpPerWindow(int maxPerIpPerWindow) { this.maxPerIpPerWindow = maxPerIpPerWindow; }

    public Duration getIpWindow() { return ipWindow; }
    public void setIpWindow(Duration ipWindow) { this.ipWindow = ipWindow; }

    public int getMaxPerWorkspacePerWindow() { return maxPerWorkspacePerWindow; }
    public void setMaxPerWorkspacePerWindow(int maxPerWorkspacePerWindow) { this.maxPerWorkspacePerWindow = maxPerWorkspacePerWindow; }

    public Duration getWorkspaceWindow() { return workspaceWindow; }
    public void setWorkspaceWindow(Duration workspaceWindow) { this.workspaceWindow = workspaceWindow; }

    public long getMinFillMillis() { return minFillMillis; }
    public void setMinFillMillis(long minFillMillis) { this.minFillMillis = minFillMillis; }
}
