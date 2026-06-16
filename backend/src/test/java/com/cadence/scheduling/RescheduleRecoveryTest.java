package com.cadence.scheduling;

import com.cadence.auth.AuthTestConfig;
import com.cadence.domain.ClaimStatus;
import com.cadence.domain.InterviewSlotClaim;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.SchedulingMode;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.integration.EmailSender;
import com.cadence.scheduler.SchedulingReaper;
import com.cadence.service.CalendarEventService;
import com.cadence.service.EmailDispatchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F20 recovery sweep determinism (FR-023, D3). The forward-vs-rollback decision is driven by persisted state:
 * a RESCHEDULE round that reached BOOKED while its parent is still BOOKED rolls FORWARD (the reaper cancels the
 * parent); a round stuck in BOOKING rolls BACK via the existing F13 stuck-BOOKING pass (parent untouched).
 * Driven by stamping {@code updatedAt} into the past — no wall-clock sleeps.
 */
class RescheduleRecoveryTest extends SchedulingItBase {

    private static final String REQ_MEMBER = "111111111111111111111111";

    @Autowired SchedulingReaper reaper;
    @MockBean CalendarEventService calendar;
    @MockBean EmailDispatchService dispatch;
    @MockBean EmailSender emailSender;

    private SchedulingRequest parentBooked() {
        InterviewTemplate t = seedTemplate(REQ_MEMBER);
        seedContactableCandidate("cand1", "Dana", "dana@x.com");
        OfferedSlot s = slot("0", Instant.parse("2026-06-20T13:00:00Z"),
            Instant.parse("2026-06-20T14:00:00Z"), List.of(REQ_MEMBER), List.of());
        return seedBookedRequest("cand1", t.getId(), "Room 1", s, REQ_MEMBER).request;
    }

    private SchedulingRequest childRound(String parentId, SchedulingStatus status, Instant updatedAt) {
        SchedulingRequest round = new SchedulingRequest();
        round.setWorkspaceId(WS);
        round.setCandidateId("cand1");
        round.setTemplateId("t");
        round.setStatus(status);
        round.setMode(SchedulingMode.RESCHEDULE);
        round.setParentRequestId(parentId);
        round.setRootRequestId(parentId);
        round.setTokenHash(hasher.hashToken(com.cadence.security.SecureTokens.newToken()));
        round.setChosenSlotId("0");
        round.setOfferedSlots(new ArrayList<>(List.of(slot("0", Instant.parse("2026-06-21T13:00:00Z"),
            Instant.parse("2026-06-21T14:00:00Z"), List.of(REQ_MEMBER), List.of()))));
        round.setSentAt(Instant.now(clock));
        round.setExpiresAt(Instant.now(clock).plusSeconds(72 * 3600));
        round.setCreatedAt(Instant.now(clock));
        round.setUpdatedAt(updatedAt);
        return mongoTemplate.save(round);
    }

    @Test
    void childBooked_parentStillBooked_reaperRollsForward_cancelsParent() {
        when(calendar.cancelBooking(eq(WS), any())).thenReturn(true);
        SchedulingRequest parent = parentBooked();
        // Child committed (BOOKED) but the forward-commit didn't finish; stamp it past the threshold.
        Instant past = AuthTestConfig.FIXED_START.minusSeconds(3600);
        SchedulingRequest child = childRound(parent.getId(), SchedulingStatus.BOOKED, past);

        reaper.sweep();

        SchedulingRequest parentAfter = mongoTemplate.findById(parent.getId(), SchedulingRequest.class);
        assertThat(parentAfter.getStatus()).isEqualTo(SchedulingStatus.RESCHEDULED);   // rolled forward
        verify(calendar, times(1)).cancelBooking(eq(WS), eq(parent.getId()));
    }

    @Test
    void childStuckBooking_reaperRollsBack_parentStands() {
        SchedulingRequest parent = parentBooked();
        Instant past = AuthTestConfig.FIXED_START.minusSeconds(3600);
        // A claim for the child so the rollback releases it.
        mongoTemplate.save(new InterviewSlotClaim(WS, REQ_MEMBER,
            Instant.parse("2026-06-21T13:00:00Z"), "childid", Instant.now(clock)));
        SchedulingRequest child = childRound(parent.getId(), SchedulingStatus.BOOKING, past);

        reaper.sweep();

        SchedulingRequest parentAfter = mongoTemplate.findById(parent.getId(), SchedulingRequest.class);
        assertThat(parentAfter.getStatus()).isEqualTo(SchedulingStatus.BOOKED);        // untouched
        SchedulingRequest childAfter = mongoTemplate.findById(child.getId(), SchedulingRequest.class);
        assertThat(childAfter.getStatus()).isEqualTo(SchedulingStatus.PENDING_SELECTION); // rolled back
    }
}
