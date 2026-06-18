package com.cadence.service;

import com.cadence.api.DashboardDtos.DashboardSnapshot;
import com.cadence.api.DashboardDtos.NoShowMetric;
import com.cadence.api.DashboardDtos.SilenceRow;
import com.cadence.api.DashboardDtos.TimeToScheduleMetric;
import com.cadence.api.DashboardWindow;
import com.cadence.api.SlaNudgeDtos.CandidateSla;
import com.cadence.config.DashboardProperties;
import com.cadence.domain.Candidate;
import com.cadence.domain.SchedulingRequest;
import com.cadence.repository.CandidateRepository;
import com.cadence.repository.SchedulingRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * F50 Core Dashboard — strictly READ-ONLY computation over existing seams (research D1/D8). Computes the three
 * MVP metrics on read; NO new collection, NO mutation, NO outbound communication.
 *
 * <p><b>Read-only is structural (SC-011):</b> this service holds NO reference to {@code EmailDispatchService},
 * {@code EmailSender}, any calendar client, or {@code AuthAuditService} (the export audit is the CONTROLLER's
 * job), and performs no repository {@code save}/{@code insert}/{@code updateFirst}/{@code findAndModify}.
 * {@code DashboardReadOnlyStructuralTest} enforces this with a constant-pool scan.
 *
 * <p>The velocity metrics read {@code schedulingRequests} (ids/instants/enums — no PII). The ONLY PII path is
 * the silence-list name join, bounded by {@code silenceListCap} (the decrypt bound). Names are never logged.
 */
@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final Clock clock;
    private final SchedulingRequestRepository scheduling;
    private final CandidateRepository candidates;
    private final SlaNudgeService slaNudge;
    private final DashboardProperties props;
    private final CsvInjectionEscaper escaper;

    public DashboardService(Clock clock, SchedulingRequestRepository scheduling, CandidateRepository candidates,
                            SlaNudgeService slaNudge, DashboardProperties props, CsvInjectionEscaper escaper) {
        this.clock = clock;
        this.scheduling = scheduling;
        this.candidates = candidates;
        this.slaNudge = slaNudge;
        this.props = props;
        this.escaper = escaper;
    }

    /** The full snapshot: velocity metrics windowed on {@code window}; the silence list is current state. */
    public DashboardSnapshot snapshot(String workspaceId, DashboardWindow window) {
        Instant now = Instant.now(clock);
        Instant windowStart = window.windowStart(now);
        return new DashboardSnapshot(window, now,
            timeToSchedule(workspaceId, windowStart, now),
            noShow(workspaceId, windowStart, now),
            silenceList(workspaceId, now));
    }

    // ---- time-to-schedule (US1) ----

    private TimeToScheduleMetric timeToSchedule(String workspaceId, Instant windowStart, Instant now) {
        List<SchedulingRequest> rows = scheduling.findBookedForVelocity(
            workspaceId, windowStart, now, PageRequest.of(0, props.getMedianSampleCap()));
        if (rows.size() >= props.getMedianSampleCap()) {
            // Honest bound (FR-001): the median is over a capped sample. Value-free log (ids/counts only).
            log.warn("dashboard time-to-schedule median sample hit the cap; figure is a bounded estimate "
                + "cap={} workspaceId={}", props.getMedianSampleCap(), workspaceId);
        }
        List<Long> minutes = new ArrayList<>();
        for (SchedulingRequest r : rows) {
            if (r.getSentAt() != null && r.getBookedAt() != null) {
                minutes.add(Duration.between(r.getSentAt(), r.getBookedAt()).toMinutes());
            }
        }
        if (minutes.isEmpty()) {
            return new TimeToScheduleMetric(false, null, 0);
        }
        minutes.sort(Comparator.naturalOrder());
        double medianMinutes = median(minutes);
        double medianHours = round1(medianMinutes / 60.0);
        return new TimeToScheduleMetric(true, medianHours, minutes.size());
    }

    /** Odd N -> middle element; even N -> arithmetic mean of the two central elements (FR-001). */
    private static double median(List<Long> sorted) {
        int n = sorted.size();
        int mid = n / 2;
        if (n % 2 == 1) {
            return sorted.get(mid);
        }
        return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

    // ---- no-show rate (US1) ----

    private NoShowMetric noShow(String workspaceId, Instant windowStart, Instant now) {
        long denom = scheduling.countNoShowDenominator(workspaceId, windowStart, now);
        if (denom == 0) {
            return new NoShowMetric(false, null, 0, 0); // "not applicable / no interviews yet" (FR-007)
        }
        long num = scheduling.countNoShows(workspaceId, windowStart, now);
        double rate = (double) num / (double) denom;
        return new NoShowMetric(true, rate, (int) num, (int) denom);
    }

    // ---- silence list (US2) ----

    private List<SilenceRow> silenceList(String workspaceId, Instant now) {
        // SlaNudgeService.silenceList already excludes GREEN/terminal/erased (via classify) and is ids-only,
        // UNORDERED, up to scanBatchLimit. The dashboard owns: sort most-overdue-first -> truncate to the cap
        // -> decrypt names for the truncated set only (the decrypt bound, FR-010/FR-012). Do NOT re-filter.
        List<CandidateSla> all = new ArrayList<>(slaNudge.silenceList(workspaceId));
        all.sort(Comparator.comparing(CandidateSla::lastActivityAt,
            Comparator.nullsFirst(Comparator.naturalOrder()))); // null activity == most overdue -> first
        List<CandidateSla> capped = all.size() > props.getSilenceListCap()
            ? all.subList(0, props.getSilenceListCap()) : all;
        if (capped.isEmpty()) {
            return List.of();
        }
        List<String> ids = capped.stream().map(CandidateSla::candidateId).toList();
        Map<String, Candidate> byId = candidates.findByWorkspaceIdAndIdIn(workspaceId, ids).stream()
            .collect(Collectors.toMap(Candidate::getId, Function.identity()));
        List<SilenceRow> out = new ArrayList<>(capped.size());
        for (CandidateSla cs : capped) {
            Candidate c = byId.get(cs.candidateId());
            String name = c != null ? c.getName() : null;
            Instant basis = basisOf(cs, c);
            long daysSilent = basis != null ? Duration.between(basis, now).toDays() : 0L;
            out.add(new SilenceRow(cs.candidateId(), name, cs.slaState().name(), daysSilent));
        }
        return out;
    }

    /**
     * daysSilent basis: {@code CandidateSla.lastActivityAt} (== the candidate's lastContactAt), falling back to
     * the candidate's createdAt when lastContactAt is null (the F31 classify basis — a RED row keyed off createdAt).
     */
    private static Instant basisOf(CandidateSla cs, Candidate c) {
        if (cs.lastActivityAt() != null) {
            return cs.lastActivityAt();
        }
        return c != null ? c.getCreatedAt() : null;
    }

    // ---- CSV export (US3) ----

    /**
     * Render the snapshot to injection-safe CSV (FR-018). Every candidate-derived cell passes through
     * {@link CsvInjectionEscaper} (F50 is its first real caller). Built in-memory; the controller streams it and
     * never persists it. The same capped snapshot feeds both the screen and this export (FR-017).
     */
    public String renderCsv(DashboardSnapshot snap) {
        StringBuilder sb = new StringBuilder();
        sb.append("Section,Metric,Value,Detail\n");
        row(sb, "Summary", "Window", snap.window().name(), "");
        row(sb, "Summary", "Generated", snap.generatedAt().toString(), "");
        TimeToScheduleMetric tts = snap.timeToSchedule();
        if (tts.hasData()) {
            row(sb, "Time to schedule", "Median hours", fmt1(tts.medianHours()), "samples=" + tts.sampleCount());
        } else {
            row(sb, "Time to schedule", "Median hours", "n/a", "no data for this window");
        }
        NoShowMetric ns = snap.noShow();
        if (ns.applicable()) {
            row(sb, "No-show", "Rate", pct(ns.rate()), ns.noShowCount() + " of " + ns.qualifyingCount());
        } else {
            row(sb, "No-show", "Rate", "n/a", "no interviews yet");
        }
        for (SilenceRow s : snap.silenceList()) {
            row(sb, "Silence list", "Candidate", s.candidateName(),
                s.severity() + "; " + s.daysSilent() + " days silent");
        }
        return sb.toString();
    }

    private void row(StringBuilder sb, String section, String metric, String value, String detail) {
        sb.append(escaper.escapeForSpreadsheet(section)).append(',')
            .append(escaper.escapeForSpreadsheet(metric)).append(',')
            .append(escaper.escapeForSpreadsheet(value == null ? "" : value)).append(',')
            .append(escaper.escapeForSpreadsheet(detail == null ? "" : detail)).append('\n');
    }

    /** rate (0..1) -> "NN.N%" HALF_UP one decimal (e.g. 2/7 -> "28.6%"). */
    private static String pct(double rate) {
        return BigDecimal.valueOf(rate * 100.0).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String fmt1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
