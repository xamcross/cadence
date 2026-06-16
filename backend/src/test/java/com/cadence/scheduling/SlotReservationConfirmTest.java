package com.cadence.scheduling;

import com.cadence.api.SchedulingExceptions;
import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.BusyInterval;
import com.cadence.domain.ClaimStatus;
import com.cadence.domain.InterviewSlotClaim;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.MemberAvailability;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.PanelBookingResult;
import com.cadence.domain.PanelBookingResult.MemberEventResult;
import com.cadence.domain.PanelBookingResult.MemberOutcome;
import com.cadence.domain.PanelBookingResult.PanelOutcome;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.integration.EmailSender;
import com.cadence.service.AvailabilityService;
import com.cadence.service.CalendarEventService;
import com.cadence.service.EmailDispatchService;
import com.cadence.service.SlotReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Confirm saga branches (FR-012..FR-019): happy CREATED -> BOOKED + ConfirmResult; idempotent replay (confirm
 * twice) -> no 2nd panel create + same result; stale slot (busy at confirm) -> StaleSlot + back to PENDING;
 * ROLLED_BACK -> BookingFailed + claims released + back to PENDING; CLEANUP_INCOMPLETE -> CleanupIncomplete +
 * request CLEANUP_INCOMPLETE. CalendarEventService + email paths are mocked for determinism.
 */
class SlotReservationConfirmTest extends SchedulingItBase {

    private static final String REQ_MEMBER = "111111111111111111111111";

    @Autowired SlotReservationService service;
    @MockBean AvailabilityService availability;
    @MockBean CalendarEventService calendar;
    @MockBean EmailDispatchService dispatch;
    @MockBean EmailSender emailSender;

    private void freeAtConfirm() {
        when(availability.query(eq(WS), any(), any(), anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(3);
            List<MemberAvailability> out = new ArrayList<>();
            for (String id : ids) out.add(new MemberAvailability(id, AvailabilityStatus.DATA, List.of()));
            return out;
        });
    }

    private void panelOutcome(PanelOutcome outcome) {
        when(calendar.createPanelEvents(eq(WS), any(), anyList(), any())).thenReturn(
            new PanelBookingResult(outcome,
                List.of(new MemberEventResult(REQ_MEMBER, MemberOutcome.CREATED, "evt-1"))));
    }

    private Seeded seedReady() {
        // Template whose required member matches the slot's required member.
        InterviewTemplate t = seedTemplate(REQ_MEMBER);
        seedContactableCandidate("cand1", "Dana", "dana@x.com");
        OfferedSlot s = slot("0", Instant.parse("2026-06-20T13:00:00Z"), Instant.parse("2026-06-20T14:00:00Z"),
            List.of(REQ_MEMBER), List.of());
        return seedPendingRequest("cand1", t.getId(), "Room 1", List.of(s));
    }

    @Test
    void happyConfirm_booksAndReturnsResult() {
        freeAtConfirm();
        panelOutcome(PanelOutcome.CREATED);
        Seeded seeded = seedReady();

        SlotReservationService.ConfirmResult r = service.confirm(seeded.rawToken, "0", "9.9.9.9");

        assertThat(r.bookedStart()).isEqualTo(Instant.parse("2026-06-20T13:00:00Z"));
        assertThat(r.zoneId()).isEqualTo("UTC");
        SchedulingRequest after = mongoTemplate.findById(seeded.request.getId(), SchedulingRequest.class);
        assertThat(after.getStatus()).isEqualTo(SchedulingStatus.BOOKED);
        assertThat(after.getChosenSlotId()).isEqualTo("0");
        verify(calendar, times(1)).createPanelEvents(eq(WS), any(), anyList(), any());
    }

    @Test
    void confirmTwice_isIdempotent_noSecondPanelCreate() {
        freeAtConfirm();
        panelOutcome(PanelOutcome.CREATED);
        Seeded seeded = seedReady();

        SlotReservationService.ConfirmResult first = service.confirm(seeded.rawToken, "0", "9.9.9.9");
        SlotReservationService.ConfirmResult second = service.confirm(seeded.rawToken, "0", "9.9.9.9");

        assertThat(second.bookedStart()).isEqualTo(first.bookedStart());
        // The replay returns from the BOOKED idempotent branch — never a 2nd provider booking.
        verify(calendar, times(1)).createPanelEvents(eq(WS), any(), anyList(), any());
    }

    @Test
    void staleSlot_busyAtConfirm_throwsStale_andRevertsToPending() {
        // Required member now busy across the slot -> not free -> stale.
        when(availability.query(eq(WS), any(), any(), anyList())).thenReturn(List.of(
            new MemberAvailability(REQ_MEMBER, AvailabilityStatus.DATA, List.of(
                new BusyInterval(Instant.parse("2026-06-20T12:00:00Z"), Instant.parse("2026-06-20T15:00:00Z"))))));
        Seeded seeded = seedReady();

        assertThatThrownBy(() -> service.confirm(seeded.rawToken, "0", "9.9.9.9"))
            .isInstanceOf(SchedulingExceptions.StaleSlotException.class);

        SchedulingRequest after = mongoTemplate.findById(seeded.request.getId(), SchedulingRequest.class);
        assertThat(after.getStatus()).isEqualTo(SchedulingStatus.PENDING_SELECTION);
        verify(calendar, never()).createPanelEvents(any(), any(), anyList(), any());
    }

    @Test
    void rolledBack_throwsBookingFailed_releasesClaims_backToPending() {
        freeAtConfirm();
        panelOutcome(PanelOutcome.ROLLED_BACK);
        Seeded seeded = seedReady();

        assertThatThrownBy(() -> service.confirm(seeded.rawToken, "0", "9.9.9.9"))
            .isInstanceOf(SchedulingExceptions.BookingFailedException.class);

        SchedulingRequest after = mongoTemplate.findById(seeded.request.getId(), SchedulingRequest.class);
        assertThat(after.getStatus()).isEqualTo(SchedulingStatus.PENDING_SELECTION);
        List<InterviewSlotClaim> claims = mongoTemplate.findAll(InterviewSlotClaim.class);
        assertThat(claims).isNotEmpty();
        assertThat(claims).allMatch(c -> c.getStatus() == ClaimStatus.RELEASED);
    }

    @Test
    void cleanupIncomplete_throwsCleanup_requestCleanupIncomplete() {
        freeAtConfirm();
        panelOutcome(PanelOutcome.CLEANUP_INCOMPLETE);
        Seeded seeded = seedReady();

        assertThatThrownBy(() -> service.confirm(seeded.rawToken, "0", "9.9.9.9"))
            .isInstanceOf(SchedulingExceptions.CleanupIncompleteException.class);

        SchedulingRequest after = mongoTemplate.findById(seeded.request.getId(), SchedulingRequest.class);
        assertThat(after.getStatus()).isEqualTo(SchedulingStatus.CLEANUP_INCOMPLETE);
    }
}
