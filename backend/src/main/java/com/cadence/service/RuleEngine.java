package com.cadence.service;

import com.cadence.api.InterviewTemplateExceptions;
import com.cadence.api.RbacExceptions;
import com.cadence.config.CalendarApiProperties;
import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.BlackoutPeriod;
import com.cadence.domain.BusyInterval;
import com.cadence.domain.ComputedSlot;
import com.cadence.domain.EventStatus;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.ManagedCalendarEvent;
import com.cadence.domain.MemberAvailability;
import com.cadence.domain.MemberUnschedulable;
import com.cadence.domain.PoolRule;
import com.cadence.domain.SlotComputationRequest;
import com.cadence.domain.SlotComputationResult;
import com.cadence.domain.TemplateStatus;
import com.cadence.domain.UnschedulableReason;
import com.cadence.domain.WorkingHours;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.repository.InterviewTemplateRepository;
import com.cadence.repository.ManagedCalendarEventRepository;
import com.cadence.repository.WorkspaceConfigRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The F12 rule engine (research D2/D4/D6/D14): given a template + a target date range, compute the
 * slots satisfying EVERY rule against the panel's real availability. Reads availability through the
 * UNCHANGED {@link AvailabilityService} (one panel-wide read per computation). Pure {@code java.time}
 * interval arithmetic; DST-correct (gap detection via a {@code LocalDateTime} round-trip); fail-safe
 * (an unknown required member yields no slot and an unknown pool member is not counted — never "free");
 * deterministic (single snapshot, injected {@link Clock}, stable ordering). Logs nothing (the service
 * logs ids only; D10).
 *
 * <p><strong>Isolation (D8)</strong>: the member ids passed to {@link AvailabilityService} come ONLY
 * from the persisted, validation-passed template — never a request-supplied list — so a Recruiter
 * cannot probe a foreign member's availability. The request carries only a template id + date range.
 */
@Service
public class RuleEngine {

    private static final List<EventStatus> CAP_EXCLUDED = List.of(EventStatus.DELETED, EventStatus.CLEANUP_INCOMPLETE);

    private final InterviewTemplateRepository templates;
    private final WorkspaceConfigRepository configs;
    private final AvailabilityService availability;
    private final ManagedCalendarEventRepository managedEvents;
    private final CalendarApiProperties calProps;
    private final Clock clock;

    public RuleEngine(InterviewTemplateRepository templates, WorkspaceConfigRepository configs,
                      AvailabilityService availability, ManagedCalendarEventRepository managedEvents,
                      CalendarApiProperties calProps, Clock clock) {
        this.templates = templates;
        this.configs = configs;
        this.availability = availability;
        this.managedEvents = managedEvents;
        this.calProps = calProps;
        this.clock = clock;
    }

    public SlotComputationResult compute(SlotComputationRequest req) {
        InterviewTemplate t = templates.findByWorkspaceIdAndId(req.workspaceId(), req.templateId())
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        if (t.getStatus() == TemplateStatus.RETIRED) {
            throw new InterviewTemplateExceptions.TemplateRetiredException();
        }

        // Resolve zone + working hours: each independently overridden by the template, else inherited
        // from the workspace config by reference at compute time (FR-018/FR-019).
        WorkspaceConfig cfg = configs.findByWorkspaceId(req.workspaceId())
            .filter(WorkspaceConfig::isConfigured).orElse(null);
        ZoneId zone = resolveZone(t, cfg);
        WorkingHours wh = t.getWorkingHoursOverride() != null ? t.getWorkingHoursOverride()
            : (cfg != null ? cfg.getWorkingHours() : null);
        if (zone == null || wh == null || wh.getStart() == null || wh.getEnd() == null) {
            throw new InterviewTemplateExceptions.WorkspaceNotConfiguredException();
        }

        // Absolute window from civil dates; clamp to the configured maximum (FR-017); never offer a past slot.
        Instant windowStart = req.rangeStart().atStartOfDay(zone).toInstant();
        Instant windowEndExclusive = req.rangeEnd().plusDays(1).atStartOfDay(zone).toInstant();
        Instant maxEnd = windowStart.plus(calProps.getMaxWindow());
        boolean clamped = windowEndExclusive.isAfter(maxEnd);
        Instant windowEnd = clamped ? maxEnd : windowEndExclusive;
        Instant now = Instant.now(clock);
        Instant effectiveStart = now.isAfter(windowStart) ? now : windowStart;
        if (!windowEnd.isAfter(effectiveStart)) {
            return new SlotComputationResult(List.of(), clamped, List.of()); // past / empty / reversed
        }

        List<String> required = t.getRequiredMemberIds();
        // Query ONLY required + pool members (optional never gates → not queried; bounds the fan-out, FR-024).
        Set<String> queryIds = new LinkedHashSet<>(required);
        for (PoolRule p : t.getPools()) {
            queryIds.addAll(p.getMemberIds());
        }
        Map<String, MemberAvailability> byMember = new HashMap<>();
        for (MemberAvailability ma : availability.query(req.workspaceId(), windowStart, windowEnd, new ArrayList<>(queryIds))) {
            byMember.put(ma.memberId(), ma);
        }

        // F20 carve-out (D7 / FR-006): the booking being rescheduled is still live on its participants'
        // calendars (D2 keeps it until forward-commit), so the provider free/busy read shows them busy at the
        // OLD time. Subtract the moved booking's own event window from each participant's busy intervals so a
        // reschedule is not falsely refused for slots adjacent to the original meeting (not just the exact
        // instant, which D6 already excludes). Scoped to the excluded booking only.
        if (req.excludeBookingRef() != null) {
            for (ManagedCalendarEvent ev : managedEvents.findByWorkspaceIdAndBookingRef(req.workspaceId(), req.excludeBookingRef())) {
                if (ev.getStatus() == EventStatus.DELETED || ev.getStartAt() == null || ev.getEndAt() == null) {
                    continue;
                }
                MemberAvailability ma = byMember.get(ev.getMemberId());
                if (ma != null && ma.status() == AvailabilityStatus.DATA) {
                    byMember.put(ev.getMemberId(), new MemberAvailability(ma.memberId(), ma.status(),
                        subtractInterval(ma.busy(), ev.getStartAt(), ev.getEndAt())));
                }
            }
        }

        // Unschedulable = required members whose availability is not DATA, with a distinguishable reason (FR-014).
        List<MemberUnschedulable> unschedulable = new ArrayList<>();
        for (String m : required.stream().sorted().toList()) {
            MemberAvailability ma = byMember.get(m);
            AvailabilityStatus status = ma == null ? AvailabilityStatus.NOT_CONNECTED : ma.status();
            UnschedulableReason reason = UnschedulableReason.from(status);
            if (reason != null) {
                unschedulable.add(new MemberUnschedulable(m, reason));
            }
        }

        // Daily cap: ONE read per required member over the window, bucketed by zone civil day (D5/D12).
        Map<String, Map<LocalDate, Integer>> existingByDay = new HashMap<>();
        for (String m : required) {
            Map<LocalDate, Integer> byDay = new HashMap<>();
            List<ManagedCalendarEvent> rows = managedEvents
                .findLiveForCap(req.workspaceId(), m, CAP_EXCLUDED, windowStart, windowEnd);
            for (ManagedCalendarEvent e : rows) {
                // F20 carve-out (D7): do not count the booking being rescheduled against its own cap.
                if (req.excludeBookingRef() != null && req.excludeBookingRef().equals(e.getBookingRef())) {
                    continue;
                }
                byDay.merge(e.getStartAt().atZone(zone).toLocalDate(), 1, Integer::sum);
            }
            existingByDay.put(m, byDay);
        }
        Map<String, Map<LocalDate, Integer>> offeredByDay = new HashMap<>();

        int duration = t.getDurationMinutes();
        int cadence = t.getSlotCadenceMinutes();
        int bb = t.getBufferBeforeMinutes();
        int ba = t.getBufferAfterMinutes();
        int cap = t.getDailyCapPerInterviewer();
        List<PoolRule> pools = t.getPools();
        List<String> requiredSorted = required.stream().sorted().toList();

        List<ComputedSlot> slots = new ArrayList<>();
        LocalDate firstDay = effectiveStart.atZone(zone).toLocalDate();
        LocalDate lastDay = windowEnd.minusNanos(1).atZone(zone).toLocalDate();

        for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            LocalDateTime whStartLdt = LocalDateTime.of(day, wh.getStart());
            LocalDateTime whEndLdt = LocalDateTime.of(day, wh.getEnd());
            for (LocalDateTime start = whStartLdt; ; start = start.plusMinutes(cadence)) {
                LocalDateTime bufStart = start.minusMinutes(bb);
                LocalDateTime endLdt = start.plusMinutes(duration);
                LocalDateTime bufEnd = start.plusMinutes((long) duration + ba);
                if (bufEnd.isAfter(whEndLdt)) {
                    break; // duration + buffers no longer fit the working window this day
                }
                if (bufStart.isBefore(whStartLdt)) {
                    continue; // buffer-before would fall outside working hours
                }
                // DST gap: a non-existent local time round-trips to a DIFFERENT LocalDateTime. Reject a
                // slot whose START is in the gap (FR-015); a meeting merely SPANNING spring-forward is
                // valid, so the end is only rejected when a non-zero buffer-after lands in the gap.
                ZonedDateTime zStart = start.atZone(zone);
                if (!zStart.toLocalDateTime().equals(start)) {
                    continue;
                }
                ZonedDateTime zBufEnd = bufEnd.atZone(zone);
                if (ba > 0 && !zBufEnd.toLocalDateTime().equals(bufEnd)) {
                    continue;
                }
                Instant sInst = zStart.toInstant();
                Instant eInst = endLdt.atZone(zone).toInstant();
                Instant bufStartInst = bufStart.atZone(zone).toInstant();
                Instant bufEndInst = zBufEnd.toInstant();

                if (sInst.isBefore(effectiveStart) || eInst.isAfter(windowEnd)) {
                    continue; // never a past slot; keep within the clamped window
                }
                if (overlapsBlackout(t.getBlackouts(), sInst, eInst)) {
                    continue; // blackout always wins
                }
                // Required: every required member positively free across the buffered window (FR-009/FR-014).
                boolean ok = true;
                for (String m : required) {
                    if (!isFree(byMember.get(m), bufStartInst, bufEndInst)) { ok = false; break; }
                }
                if (!ok) {
                    continue;
                }
                // Daily cap (required only at compute time; pool-member cap is F13's, FR-012).
                for (String m : required) {
                    int ex = existingByDay.getOrDefault(m, Map.of()).getOrDefault(day, 0);
                    int off = offeredByDay.getOrDefault(m, Map.of()).getOrDefault(day, 0);
                    if (ex + off >= cap) { ok = false; break; }
                }
                if (!ok) {
                    continue;
                }
                // Pools: each reaches quorum on DISTINCT positively-free members; annotate per pool (FR-010).
                Map<Integer, List<String>> qualifying = new LinkedHashMap<>();
                boolean poolsOk = true;
                for (int pi = 0; pi < pools.size(); pi++) {
                    PoolRule p = pools.get(pi);
                    List<String> q = new ArrayList<>();
                    for (String m : p.getMemberIds()) {
                        if (isFree(byMember.get(m), bufStartInst, bufEndInst) && !q.contains(m)) {
                            q.add(m);
                        }
                    }
                    if (q.size() < p.getN()) { poolsOk = false; break; }
                    q.sort(String::compareTo);
                    qualifying.put(pi, q);
                }
                if (!poolsOk) {
                    continue;
                }
                slots.add(new ComputedSlot(sInst, eInst, zone.getId(), requiredSorted, qualifying));
                for (String m : required) {
                    offeredByDay.computeIfAbsent(m, k -> new HashMap<>()).merge(day, 1, Integer::sum);
                }
            }
        }
        return new SlotComputationResult(slots, clamped, unschedulable);
    }

    private static ZoneId resolveZone(InterviewTemplate t, WorkspaceConfig cfg) {
        if (t.getTimeZoneOverride() != null) {
            return ZoneId.of(t.getTimeZoneOverride());
        }
        return cfg != null && cfg.getTimeZone() != null ? ZoneId.of(cfg.getTimeZone()) : null;
    }

    /** Remove [cs, ce) from a busy-interval list (interval subtraction), keeping any left/right remainders. */
    private static List<BusyInterval> subtractInterval(List<BusyInterval> busy, Instant cs, Instant ce) {
        List<BusyInterval> out = new ArrayList<>();
        for (BusyInterval b : busy) {
            if (!(b.start().isBefore(ce) && cs.isBefore(b.end()))) {
                out.add(b);                                            // no overlap — keep
                continue;
            }
            if (b.start().isBefore(cs)) {
                out.add(new BusyInterval(b.start(), cs));              // left remainder
            }
            if (ce.isBefore(b.end())) {
                out.add(new BusyInterval(ce, b.end()));                // right remainder
            }
        }
        return out;
    }

    /** Free == positively-known (status DATA) AND no busy interval overlaps [from, to). Half-open. */
    private static boolean isFree(MemberAvailability ma, Instant from, Instant to) {
        if (ma == null || ma.status() != AvailabilityStatus.DATA) {
            return false; // unknown availability is NEVER assumed free (FR-014)
        }
        for (BusyInterval b : ma.busy()) {
            if (b.start().isBefore(to) && from.isBefore(b.end())) {
                return false;
            }
        }
        return true;
    }

    private static boolean overlapsBlackout(List<BlackoutPeriod> blackouts, Instant from, Instant to) {
        for (BlackoutPeriod b : blackouts) {
            if (b.getStart().isBefore(to) && from.isBefore(b.getEnd())) {
                return true;
            }
        }
        return false;
    }
}
