package com.cadence.api;

import java.time.Instant;
import java.util.List;

/**
 * F50 Core Dashboard wire DTOs (contracts/dashboard-api.md). All value-free EXCEPT {@code SilenceRow.candidateName}
 * (the minimum-necessary identifier on this authenticated internal screen — never email/phone; FR-012). The
 * velocity metrics are PII-free by construction (computed over {@code schedulingRequests}, which carry no PII).
 */
public final class DashboardDtos {

    private DashboardDtos() {}

    /** The full read-on snapshot for a workspace + window (metrics windowed; the silence list is current). */
    public record DashboardSnapshot(DashboardWindow window, Instant generatedAt,
                                    TimeToScheduleMetric timeToSchedule, NoShowMetric noShow,
                                    List<SilenceRow> silenceList) {}

    /** Median time from scheduling-link-sent to slot-confirmed. {@code hasData=false} -> empty state (FR-002). */
    public record TimeToScheduleMetric(boolean hasData, Double medianHours, int sampleCount) {}

    /** No-show proportion. {@code applicable=false} when the denominator is zero -> "not applicable" (FR-007). */
    public record NoShowMetric(boolean applicable, Double rate, int noShowCount, int qualifyingCount) {}

    /** One silence-list entry — name + internal id + severity + whole-days silent. No email/phone (FR-012). */
    public record SilenceRow(String candidateId, String candidateName, String severity, long daysSilent) {}
}
