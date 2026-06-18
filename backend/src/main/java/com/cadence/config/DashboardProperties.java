package com.cadence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * F50 Core Dashboard global knobs ({@code cadence.dashboard.*}). The silence list is bounded by
 * {@code silenceListCap} (FR-010) — this is ALSO the per-request name-decrypt bound. The velocity median read
 * is bounded by {@code medianSampleCap} as a DoS backstop (the F12 unbounded-read lesson); at MVP volumes it is
 * never hit.
 */
@Component
@ConfigurationProperties(prefix = "cadence.dashboard")
public class DashboardProperties {

    /** Max silence-list rows returned + the per-request candidate-name decrypt bound (FR-010/FR-012). */
    private int silenceListCap = 100;

    /** Max booked rows materialised for the time-to-schedule median (DoS backstop; never hit at MVP volumes). */
    private int medianSampleCap = 5000;

    public int getSilenceListCap() { return silenceListCap; }
    public void setSilenceListCap(int silenceListCap) { this.silenceListCap = silenceListCap; }

    public int getMedianSampleCap() { return medianSampleCap; }
    public void setMedianSampleCap(int medianSampleCap) { this.medianSampleCap = medianSampleCap; }
}
