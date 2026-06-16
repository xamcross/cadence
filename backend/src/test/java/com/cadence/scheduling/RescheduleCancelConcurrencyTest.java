package com.cadence.scheduling;

import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.MemberAvailability;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.PanelBookingResult;
import com.cadence.domain.PanelBookingResult.MemberEventResult;
import com.cadence.domain.PanelBookingResult.MemberOutcome;
import com.cadence.domain.PanelBookingResult.PanelOutcome;
import com.cadence.domain.SchedulingMode;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.integration.EmailSender;
import com.cadence.service.AvailabilityService;
import com.cadence.service.CalendarEventService;
import com.cadence.service.EmailDispatchService;
import com.cadence.service.SlotReservationService;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * F20 SC-004: a reschedule confirm racing a cancel on the same booking must produce exactly ONE outcome with
 * no split state — never a cancelled booking that still carries a live reschedule, and never two live bookings.
 * Multi-trial ({@code @RepeatedTest}), barrier-synchronised, against the REAL Mongo {@code findAndModify} CAS
 * (calendar/availability mocked only for determinism). Invariant asserted: the parent ends RESCHEDULED (with
 * the child BOOKED) XOR CANCELLED (with the child NOT BOOKED).
 */
class RescheduleCancelConcurrencyTest extends SchedulingItBase {

    private static final String REQ_MEMBER = "111111111111111111111111";

    @Autowired SlotReservationService service;
    @MockBean AvailabilityService availability;
    @MockBean CalendarEventService calendar;
    @MockBean EmailDispatchService dispatch;
    @MockBean EmailSender emailSender;

    @RepeatedTest(8)
    void rescheduleConfirm_racingCancel_yieldsExactlyOneOutcome_noSplitState(RepetitionInfo rep) throws Exception {
        String ip = "10.0." + rep.getCurrentRepetition() + ".1";
        when(availability.query(eq(WS), any(), any(), anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(3);
            List<MemberAvailability> out = new ArrayList<>();
            for (String id : ids) out.add(new MemberAvailability(id, AvailabilityStatus.DATA, List.of()));
            return out;
        });
        when(calendar.createPanelEvents(eq(WS), any(), anyList(), any())).thenReturn(
            new PanelBookingResult(PanelOutcome.CREATED,
                List.of(new MemberEventResult(REQ_MEMBER, MemberOutcome.CREATED, "evt-1"))));
        when(calendar.cancelBooking(eq(WS), any())).thenReturn(true);

        configuredWorkspace();
        InterviewTemplate t = seedTemplate(REQ_MEMBER);
        seedContactableCandidate("cand1", "Dana", "dana@x.com");
        OfferedSlot s = slot("0", Instant.parse("2026-06-20T13:00:00Z"), Instant.parse("2026-06-20T14:00:00Z"),
            List.of(REQ_MEMBER), List.of());
        Seeded b = seedBookedRequest("cand1", t.getId(), "Room 1", s, REQ_MEMBER);

        // Open a reschedule round up-front; the race is confirm(child) vs cancel(parent).
        SlotReservationService.OpenRescheduleResult open = service.openReschedule(b.rawToken, ip);
        String rescheduleToken = open.rescheduleToken();
        String slotId = open.slots().get(0).slotId();

        CountDownLatch start = new CountDownLatch(1);
        Runnable rescheduleTask = () -> {
            try { start.await(); service.confirm(rescheduleToken, slotId, ip); } catch (Exception ignored) {}
        };
        Runnable cancelTask = () -> {
            try { start.await(); service.cancel(b.rawToken, ip); } catch (Exception ignored) {}
        };
        Thread a = new Thread(rescheduleTask);
        Thread c = new Thread(cancelTask);
        a.start(); c.start();
        start.countDown();
        a.join(); c.join();

        SchedulingRequest parent = mongoTemplate.findById(b.request.getId(), SchedulingRequest.class);
        SchedulingRequest child = mongoTemplate.find(
            Query.query(Criteria.where("mode").is(SchedulingMode.RESCHEDULE)), SchedulingRequest.class).get(0);

        boolean rescheduleWon = parent.getStatus() == SchedulingStatus.RESCHEDULED
            && child.getStatus() == SchedulingStatus.BOOKED;
        boolean cancelWon = parent.getStatus() == SchedulingStatus.CANCELLED
            && child.getStatus() != SchedulingStatus.BOOKED;
        assertThat(rescheduleWon ^ cancelWon)
            .as("exactly one of {reschedule, cancel} commits — parent=%s child=%s",
                parent.getStatus(), child.getStatus())
            .isTrue();
    }
}
