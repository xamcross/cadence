package com.cadence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * F31 SLA Nudge Engine global knobs ({@code cadence.sla.*}). The silence window is per-workspace
 * ({@code WorkspaceConfig.slaSilenceWindowDays}); these are the global amber margin, the default-window
 * fallback (only for a configured-but-zero edge — the scan skips unconfigured workspaces), and the scan
 * batch cap. The scan fixed delay is read directly by {@code @Scheduled} from {@code scan-interval-ms}.
 */
@Component
@ConfigurationProperties(prefix = "cadence.sla")
public class SlaProperties {

    /** Days before breach at which a candidate flips to AMBER (the nearing-breach margin). */
    private int amberMarginDays = 1;

    /** Fallback window when a configured workspace carries a zero/unset silence window. */
    private int defaultWindowDays = 5;

    /** Per-workspace page cap on the breach scan (index-backed bounded read). */
    private int scanBatchLimit = 500;

    public int getAmberMarginDays() { return amberMarginDays; }
    public void setAmberMarginDays(int amberMarginDays) { this.amberMarginDays = amberMarginDays; }

    public int getDefaultWindowDays() { return defaultWindowDays; }
    public void setDefaultWindowDays(int defaultWindowDays) { this.defaultWindowDays = defaultWindowDays; }

    public int getScanBatchLimit() { return scanBatchLimit; }
    public void setScanBatchLimit(int scanBatchLimit) { this.scanBatchLimit = scanBatchLimit; }
}
