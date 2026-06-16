package com.cadence.scheduling;

import com.cadence.api.SchedulingExceptions;
import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.ClaimStatus;
import com.cadence.domain.ErasureState;
import com.cadence.domain.InterviewSlotClaim;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.MemberAvailability;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.integration.EmailSender;
import com.cadence.service.AvailabilityService;
import com.cadence.service.CalendarEventService;
import com.cadence.service.CandidateErasureService;
import com.cadence.service.EmailDispatchService;
import com.cadence.service.SlotReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-014 erasure interaction: a candidate erased after the link was sent cannot book (NotAvailable, byte-
 * identical across deny reasons, no provider call); and {@link CandidateErasureService#wipe} supersedes a
 * candidate's live scheduling request + releases its ACTIVE claims (research D10) so no bookable link / held
 * slot survives erasure.
 */
class SlotReservationErasureTest extends SchedulingItBase {

    private static final String REQ_MEMBER = "111111111111111111111111";

    @Autowired SlotReservationService service;
    @Autowired CandidateErasureService erasure;
    @MockBean AvailabilityService availability;
    @MockBean CalendarEventService calendar;
    @MockBean EmailDispatchService dispatch;
    @MockBean EmailSender emailSender;

    private OfferedSlot openSlot() {
        return slot("0", Instant.parse("2026-06-20T13:00:00Z"), Instant.parse("2026-06-20T14:00:00Z"),
            List.of(REQ_MEMBER), List.of());
    }

    @Test
    void erasedCandidateAtConfirm_throwsNotAvailable_noBooking() {
        when(availability.query(eq(WS), any(), any(), anyList())).thenReturn(List.of(
            new MemberAvailability(REQ_MEMBER, AvailabilityStatus.DATA, List.of())));
        InterviewTemplate t = seedTemplate(REQ_MEMBER);
        // Candidate exists but is ERASED -> gate denies at confirm.
        var c = newCandidate("cand1", "Dana", "dana@x.com");
        c.setErasureState(ErasureState.ERASED);
        mongoTemplate.save(c);
        Seeded seeded = seedPendingRequest("cand1", t.getId(), "Room 1", List.of(openSlot()));

        assertThatThrownBy(() -> service.confirm(seeded.rawToken, "0", "9.9.9.9"))
            .isInstanceOf(SchedulingExceptions.NotAvailableException.class);

        SchedulingRequest after = mongoTemplate.findById(seeded.request.getId(), SchedulingRequest.class);
        assertThat(after.getStatus()).isEqualTo(SchedulingStatus.PENDING_SELECTION); // reverted, not booked
        verify(calendar, never()).createPanelEvents(any(), any(), anyList(), any());
    }

    @Test
    void wipe_supersedesLiveRequest_andReleasesActiveClaims() {
        seedContactableCandidate("cand1", "Dana", "dana@x.com");
        InterviewTemplate t = seedTemplate(REQ_MEMBER);
        Seeded seeded = seedPendingRequest("cand1", t.getId(), "Room 1", List.of(openSlot()));
        // An ACTIVE claim tied to the live request (e.g. an in-flight booking).
        InterviewSlotClaim claim = mongoTemplate.save(new InterviewSlotClaim(
            WS, REQ_MEMBER, Instant.parse("2026-06-20T13:00:00Z"), seeded.request.getId(), Instant.now(clock)));

        boolean wiped = erasure.wipe(WS, "cand1", CandidateAuditOutcome.OPERATOR, "admin1");
        assertThat(wiped).isTrue();

        SchedulingRequest after = mongoTemplate.findById(seeded.request.getId(), SchedulingRequest.class);
        assertThat(after.getStatus()).isEqualTo(SchedulingStatus.SUPERSEDED);
        InterviewSlotClaim afterClaim = mongoTemplate.findById(claim.getId(), InterviewSlotClaim.class);
        assertThat(afterClaim.getStatus()).isEqualTo(ClaimStatus.RELEASED);
    }
}
