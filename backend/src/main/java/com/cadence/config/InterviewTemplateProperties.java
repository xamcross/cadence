package com.cadence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code interview.template.*} block (F12). {@code default-slot-cadence-minutes} is the
 * cadence used when a template does not set one (D3); {@code max-members}/{@code max-pools}/
 * {@code max-blackouts} bound a single template so a huge pool + max window cannot amplify the
 * availability fan-out into a DoS / mass-probe (FR-024/D8). The compute-window clamp reuses
 * {@code calendar.api.max-window} (no new key). No secrets.
 */
@ConfigurationProperties(prefix = "interview.template")
public class InterviewTemplateProperties {

    private int defaultSlotCadenceMinutes = 15;
    private int maxMembers = 25;
    private int maxPools = 10;
    private int maxBlackouts = 50;

    public int getDefaultSlotCadenceMinutes() { return defaultSlotCadenceMinutes; }
    public void setDefaultSlotCadenceMinutes(int v) { this.defaultSlotCadenceMinutes = v; }

    public int getMaxMembers() { return maxMembers; }
    public void setMaxMembers(int v) { this.maxMembers = v; }

    public int getMaxPools() { return maxPools; }
    public void setMaxPools(int v) { this.maxPools = v; }

    public int getMaxBlackouts() { return maxBlackouts; }
    public void setMaxBlackouts(int v) { this.maxBlackouts = v; }
}
