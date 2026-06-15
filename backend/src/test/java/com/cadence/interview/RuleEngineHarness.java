package com.cadence.interview;

import com.cadence.config.CalendarApiProperties;
import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.BusyInterval;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.ManagedCalendarEvent;
import com.cadence.domain.MemberAvailability;
import com.cadence.domain.PoolRule;
import com.cadence.domain.TemplateStatus;
import com.cadence.domain.WorkingHours;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.repository.InterviewTemplateRepository;
import com.cadence.repository.ManagedCalendarEventRepository;
import com.cadence.repository.WorkspaceConfigRepository;
import com.cadence.service.AvailabilityService;
import com.cadence.service.RuleEngine;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-unit harness for {@link RuleEngine}: Mockito mocks for the repos + {@link AvailabilityService},
 * a fixed {@link Clock}, and a real {@link CalendarApiProperties} (60d max window). Lets the RuleEngine
 * be exercised with seeded availability and cap rows, no Spring context / no Docker — fast and
 * deterministic. The availability mock returns a per-member seeded status (default: DATA + free).
 */
final class RuleEngineHarness {

    static final String WS = "ws1";

    final InterviewTemplateRepository templates = mock(InterviewTemplateRepository.class);
    final WorkspaceConfigRepository configs = mock(WorkspaceConfigRepository.class);
    final AvailabilityService availability = mock(AvailabilityService.class);
    final ManagedCalendarEventRepository managedEvents = mock(ManagedCalendarEventRepository.class);
    final CalendarApiProperties calProps = new CalendarApiProperties();
    final RuleEngine engine;

    private final Map<String, MemberAvailability> avail = new HashMap<>();
    private final Map<String, List<ManagedCalendarEvent>> capRows = new HashMap<>();
    private final java.util.Set<String> omitted = new java.util.HashSet<>();

    RuleEngineHarness(Instant now) {
        this.engine = new RuleEngine(templates, configs, availability, managedEvents, calProps,
            Clock.fixed(now, ZoneOffset.UTC));
        // Availability: return the seeded status for each requested id; default to DATA + free.
        lenient().when(availability.query(eq(WS), any(), any(), anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(3);
            List<MemberAvailability> out = new ArrayList<>();
            for (String id : ids) {
                if (omitted.contains(id)) {
                    continue; // model AvailabilityService returning NO row for this member (defensive branch)
                }
                out.add(avail.getOrDefault(id, new MemberAvailability(id, AvailabilityStatus.DATA, List.of())));
            }
            return out;
        });
        // Cap rows: seeded per member; default none.
        lenient().when(managedEvents.findLiveForCap(eq(WS), anyString(), any(), any(), any()))
            .thenAnswer(inv -> capRows.getOrDefault(inv.getArgument(1), List.of()));
    }

    RuleEngineHarness configured(String zone, LocalTime start, LocalTime end) {
        WorkspaceConfig c = new WorkspaceConfig();
        c.setWorkspaceId(WS);
        c.setConfiguredAt(Instant.parse("2026-01-01T00:00:00Z"));
        c.setTimeZone(zone);
        c.setWorkingHours(new WorkingHours(start, end));
        lenient().when(configs.findByWorkspaceId(WS)).thenReturn(Optional.of(c));
        return this;
    }

    RuleEngineHarness unconfigured() {
        lenient().when(configs.findByWorkspaceId(WS)).thenReturn(Optional.empty());
        return this;
    }

    RuleEngineHarness template(InterviewTemplate t) {
        when(templates.findByWorkspaceIdAndId(WS, t.getId())).thenReturn(Optional.of(t));
        return this;
    }

    RuleEngineHarness busy(String memberId, Instant from, Instant to) {
        avail.put(memberId, new MemberAvailability(memberId, AvailabilityStatus.DATA, List.of(new BusyInterval(from, to))));
        return this;
    }

    RuleEngineHarness free(String memberId) {
        avail.put(memberId, new MemberAvailability(memberId, AvailabilityStatus.DATA, List.of()));
        return this;
    }

    RuleEngineHarness status(String memberId, AvailabilityStatus status) {
        avail.put(memberId, new MemberAvailability(memberId, status, List.of()));
        return this;
    }

    /** Make the availability read return NO row for this member (the defensive ma==null branch). */
    RuleEngineHarness omit(String memberId) {
        omitted.add(memberId);
        return this;
    }

    /** Seed N existing Cadence-managed interviews for a member on a given day (cap fixtures). */
    RuleEngineHarness managed(String memberId, Instant... starts) {
        List<ManagedCalendarEvent> rows = new ArrayList<>();
        for (Instant s : starts) {
            ManagedCalendarEvent e = new ManagedCalendarEvent();
            e.setMemberId(memberId);
            e.setStartAt(s);
            rows.add(e);
        }
        capRows.put(memberId, rows);
        return this;
    }

    // --- template builders ---

    static InterviewTemplate template(String id, int durationMin, int cadenceMin, int bufBefore, int bufAfter,
                                      int cap, List<String> required, List<PoolRule> pools) {
        InterviewTemplate t = new InterviewTemplate();
        t.setId(id);
        t.setWorkspaceId(WS);
        t.setName("Phone Screen");
        t.setStatus(TemplateStatus.ACTIVE);
        t.setDurationMinutes(durationMin);
        t.setSlotCadenceMinutes(cadenceMin);
        t.setBufferBeforeMinutes(bufBefore);
        t.setBufferAfterMinutes(bufAfter);
        t.setDailyCapPerInterviewer(cap);
        t.setRequiredMemberIds(new ArrayList<>(required));
        t.setPools(new ArrayList<>(pools));
        return t;
    }

    static PoolRule pool(int n, String... members) {
        return new PoolRule(List.of(members), n);
    }
}
