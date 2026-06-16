package com.cadence.scheduling;

import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.EventStatus;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.ManagedCalendarEvent;
import com.cadence.domain.MemberAvailability;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.integration.EmailSender;
import com.cadence.service.AvailabilityService;
import com.cadence.service.CalendarEventService;
import com.cadence.service.EmailDispatchService;
import com.cadence.service.SlotReservationService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * F20 isolation + PII discipline. IDOR (FR-017a/SC-014): the booking is resolved SOLELY from the manage
 * credential — one candidate's token can never affect another's booking. Carve-out (D7): a reschedule is not
 * falsely refused "no slots" because the moved booking's own event consumes the interviewer's daily cap. PII
 * (FR-026/SC-010): the candidate name + recruiter location never appear in the candidate payload or the
 * persisted document (manage token is HMAC-only; location encrypted). Uses SENTINELF20* tokens (ci.yml scan).
 */
class RescheduleIsolationAndPiiTest extends SchedulingItBase {

    private static final String REQ_MEMBER = "111111111111111111111111";

    @Autowired SlotReservationService service;
    @MockBean AvailabilityService availability;
    @MockBean CalendarEventService calendar;
    @MockBean EmailDispatchService dispatch;
    @MockBean EmailSender emailSender;

    private void free() {
        when(availability.query(eq(WS), any(), any(), anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(3);
            List<MemberAvailability> out = new ArrayList<>();
            for (String id : ids) out.add(new MemberAvailability(id, AvailabilityStatus.DATA, List.of()));
            return out;
        });
    }

    @Test
    void manageToken_isBoundToOneBooking_cannotAffectAnother() {   // SC-014 (IDOR)
        when(calendar.cancelBooking(eq(WS), any())).thenReturn(true);
        InterviewTemplate t = seedTemplate(REQ_MEMBER);
        seedContactableCandidate("candX", "Xavier", "x@x.com");
        seedContactableCandidate("candY", "Yvonne", "y@y.com");
        OfferedSlot sx = slot("0", Instant.parse("2026-06-20T13:00:00Z"), Instant.parse("2026-06-20T14:00:00Z"),
            List.of(REQ_MEMBER), List.of());
        OfferedSlot sy = slot("0", Instant.parse("2026-06-21T13:00:00Z"), Instant.parse("2026-06-21T14:00:00Z"),
            List.of(REQ_MEMBER), List.of());
        Seeded x = seedBookedRequest("candX", t.getId(), "Room X", sx, REQ_MEMBER);
        Seeded y = seedBookedRequest("candY", t.getId(), "Room Y", sy, REQ_MEMBER);

        // X's manage token cancels ONLY X — there is no request field by which Y could be targeted.
        service.cancel(x.rawToken, "9.9.9.9");

        assertThat(mongoTemplate.findById(x.request.getId(), SchedulingRequest.class).getStatus())
            .isEqualTo(SchedulingStatus.CANCELLED);
        assertThat(mongoTemplate.findById(y.request.getId(), SchedulingRequest.class).getStatus())
            .isEqualTo(SchedulingStatus.BOOKED);   // untouched
    }

    @Test
    void reschedule_isNotFalselyRefused_whenParentEventConsumesTheCap() {   // D7 carve-out
        free();
        configuredWorkspace();
        // A template whose interviewer cap is 1.
        InterviewTemplate t = seedTemplate(REQ_MEMBER);
        t.setDailyCapPerInterviewer(1);
        mongoTemplate.save(t);
        seedContactableCandidate("cand1", "Dana", "dana@x.com");
        OfferedSlot s = slot("0", Instant.parse("2026-06-20T13:00:00Z"), Instant.parse("2026-06-20T14:00:00Z"),
            List.of(REQ_MEMBER), List.of());
        Seeded b = seedBookedRequest("cand1", t.getId(), "Room 1", s, REQ_MEMBER);
        // The parent booking's calendar event sits on the interviewer's calendar (consumes the cap=1).
        ManagedCalendarEvent ev = new ManagedCalendarEvent();
        ev.setWorkspaceId(WS);
        ev.setBookingRef(b.request.getId());
        ev.setMemberId(REQ_MEMBER);
        ev.setProvider(com.cadence.domain.CalendarProvider.GOOGLE);
        ev.setProviderEventId("evt-1");
        ev.setStatus(EventStatus.CREATED);
        ev.setStartAt(s.getStart());
        ev.setEndAt(s.getEnd());
        ev.setCreatedAt(Instant.now(clock));
        ev.setUpdatedAt(Instant.now(clock));
        mongoTemplate.save(ev);

        // Without the carve-out, cap=1 already consumed -> zero slots. With it, the moved booking is excluded.
        SlotReservationService.OpenRescheduleResult r = service.openReschedule(b.rawToken, "9.9.9.9");
        assertThat(r.slots()).isNotEmpty();
    }

    @Test
    void reschedule_doesNotLeakCandidateNameOrLocation() {   // SC-010 (uses ci.yml SENTINELF20* scan)
        free();
        configuredWorkspace();
        InterviewTemplate t = seedTemplate(REQ_MEMBER);
        seedContactableCandidate("cand1", "SENTINELF20NAME_zz9", "dana@x.com");
        OfferedSlot s = slot("0", Instant.parse("2026-06-20T13:00:00Z"), Instant.parse("2026-06-20T14:00:00Z"),
            List.of(REQ_MEMBER), List.of());
        Seeded b = seedBookedRequest("cand1", t.getId(), "SENTINELF20LOC_zz9", s, REQ_MEMBER);

        SlotReservationService.BookingView view = service.viewBooking(b.rawToken, "9.9.9.9");
        SlotReservationService.OpenRescheduleResult open = service.openReschedule(b.rawToken, "9.9.9.9");

        // The candidate-facing payloads carry times only — never the name or the recruiter location.
        assertThat(view.toString()).doesNotContain("SENTINELF20NAME_zz9").doesNotContain("SENTINELF20LOC_zz9");
        assertThat(open.toString()).doesNotContain("SENTINELF20NAME_zz9").doesNotContain("SENTINELF20LOC_zz9");
        // The persisted reschedule round stores location ENCRYPTED — the sentinel never appears in raw BSON.
        for (Document d : mongoTemplate.getCollection("schedulingRequests").find()) {
            assertThat(d.toJson()).doesNotContain("SENTINELF20NAME_zz9").doesNotContain("SENTINELF20LOC_zz9");
        }
    }
}
