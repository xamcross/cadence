package com.cadence.scheduling;

import com.cadence.api.SchedulingExceptions;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.ClaimStatus;
import com.cadence.domain.InterviewSlotClaim;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.MemberAvailability;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.PanelBookingResult;
import com.cadence.domain.PanelBookingResult.MemberEventResult;
import com.cadence.domain.PanelBookingResult.MemberOutcome;
import com.cadence.domain.PanelBookingResult.PanelOutcome;
import com.cadence.domain.RecruiterNotification;
import com.cadence.domain.RecruiterNotificationType;
import com.cadence.domain.SchedulingMode;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.integration.EmailSender;
import com.cadence.service.AvailabilityService;
import com.cadence.service.CalendarEventService;
import com.cadence.service.EmailDispatchService;
import com.cadence.service.SchedulingService;
import com.cadence.service.SlotReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F20 reschedule/cancel service saga (US1/US3). The reschedule books the new time under a NEW round and
 * cancels the parent only after the new round commits (D2). Asserts: atomic swap (parent RESCHEDULED, new
 * BOOKED, old events cancelled); original-preserved-on-failure (SC-003); same-time no-op + cap-not-consumed
 * (SC-013/FR-027); cancel removes events + releases claim + notifies recruiter (US3); cap-reached invalidates
 * the link + notifies (FR-005). RuleEngine is REAL (drives the reschedule slot compute off mocked availability);
 * calendar + email are mocked for determinism.
 */
class BookingManagementTest extends SchedulingItBase {

    private static final String REQ_MEMBER = "111111111111111111111111";
    private static final Instant BOOKED_START = Instant.parse("2026-06-20T13:00:00Z");
    private static final Instant BOOKED_END = Instant.parse("2026-06-20T14:00:00Z");

    @Autowired SlotReservationService service;
    @Autowired SchedulingService scheduling;
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

    private void panel(PanelOutcome outcome) {
        when(calendar.createPanelEvents(eq(WS), any(), anyList(), any())).thenReturn(
            new PanelBookingResult(outcome,
                List.of(new MemberEventResult(REQ_MEMBER, MemberOutcome.CREATED, "evt-1"))));
    }

    private Seeded booked() {
        configuredWorkspace();
        InterviewTemplate t = seedTemplate(REQ_MEMBER);
        seedContactableCandidate("cand1", "Dana", "dana@x.com");
        OfferedSlot s = slot("0", BOOKED_START, BOOKED_END, List.of(REQ_MEMBER), List.of());
        return seedBookedRequest("cand1", t.getId(), "Room 1", s, REQ_MEMBER);
    }

    @Test
    void reschedule_swapsAtomically_parentRescheduled_newBooked_oldEventsCancelled() {
        free();
        panel(PanelOutcome.CREATED);
        when(calendar.cancelBooking(eq(WS), any())).thenReturn(true);
        Seeded b = booked();

        SlotReservationService.OpenRescheduleResult open = service.openReschedule(b.rawToken, "9.9.9.9");
        assertThat(open.slots()).isNotEmpty();
        // FR-006: the currently-booked instant is never offered.
        assertThat(open.slots()).noneMatch(s -> s.start().equals(BOOKED_START));

        String slotId = open.slots().get(0).slotId();
        service.confirm(open.rescheduleToken(), slotId, "9.9.9.9");

        SchedulingRequest parent = mongoTemplate.findById(b.request.getId(), SchedulingRequest.class);
        assertThat(parent.getStatus()).isEqualTo(SchedulingStatus.RESCHEDULED);
        List<SchedulingRequest> rounds = mongoTemplate.find(
            Query.query(Criteria.where("mode").is(SchedulingMode.RESCHEDULE)), SchedulingRequest.class);
        assertThat(rounds).anyMatch(r -> r.getStatus() == SchedulingStatus.BOOKED);
        verify(calendar, times(1)).createPanelEvents(eq(WS), any(), anyList(), any());  // SC-002 new events created
        verify(calendar, times(1)).cancelBooking(eq(WS), eq(b.request.getId()));        // old events removed
        // SC-010: exactly one append-only reschedule audit entry.
        long audits = mongoTemplate.find(Query.query(Criteria.where("eventType").is(AuthEventType.SCHEDULING_RESCHEDULED)),
            AuthAuditEvent.class).size();
        assertThat(audits).isEqualTo(1L);
    }

    @Test
    void clearedManageTokens_doNotCollideOnThePartialUniqueIndex() {   // the F01 present-as-null regression
        Seeded x = booked();
        seedContactableCandidate("cand2", "Eve", "eve@x.com");
        // A distinct interviewer-time so the two ACTIVE claims don't collide (that guard is separate); the
        // point here is the manageTokenHash partial-unique index after BOTH are $unset.
        OfferedSlot s2 = slot("0", Instant.parse("2026-06-22T13:00:00Z"), Instant.parse("2026-06-22T14:00:00Z"),
            List.of(REQ_MEMBER), List.of());
        Seeded y = seedBookedRequest("cand2", x.request.getTemplateId(), "Room 2", s2, REQ_MEMBER);
        // $unset both manage tokens (the cap/erasure path). Two present-as-null keys would collide on a SPARSE
        // unique index; with the partial {$exists:true} + write=NON_NULL they are simply absent — no collision.
        for (String id : List.of(x.request.getId(), y.request.getId())) {
            mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new org.springframework.data.mongodb.core.query.Update().unset("manageTokenHash"), SchedulingRequest.class);
        }
        assertThat(mongoTemplate.findById(x.request.getId(), SchedulingRequest.class).getManageTokenHash()).isNull();
        assertThat(mongoTemplate.findById(y.request.getId(), SchedulingRequest.class).getManageTokenHash()).isNull();
    }

    @Test
    void reschedule_newEventFailure_preservesOriginalBooking() {   // SC-003
        free();
        panel(PanelOutcome.ROLLED_BACK);
        Seeded b = booked();

        SlotReservationService.OpenRescheduleResult open = service.openReschedule(b.rawToken, "9.9.9.9");
        assertThatThrownBy(() -> service.confirm(open.rescheduleToken(), open.slots().get(0).slotId(), "9.9.9.9"))
            .isInstanceOf(SchedulingExceptions.BookingFailedException.class);

        SchedulingRequest parent = mongoTemplate.findById(b.request.getId(), SchedulingRequest.class);
        assertThat(parent.getStatus()).isEqualTo(SchedulingStatus.BOOKED);          // original intact
        // The parent's manage token survives — the candidate keeps a usable booking.
        assertThat(parent.getManageTokenHash()).isNotNull();
    }

    @Test
    void sameTimeConfirm_isNoOp_noChurn_capNotConsumed() {   // FR-027 / SC-013
        free();
        Seeded b = booked();
        // Seed a RESCHEDULE round whose only offered slot IS the parent's booked instant.
        OfferedSlot same = slot("0", BOOKED_START, BOOKED_END, List.of(REQ_MEMBER), List.of());
        Seeded round = seedRescheduleRound(b.request.getId(), same);

        SlotReservationService.ConfirmResult r = service.confirm(round.rawToken, "0", "9.9.9.9");
        assertThat(r.bookedStart()).isEqualTo(BOOKED_START);

        SchedulingRequest parent = mongoTemplate.findById(b.request.getId(), SchedulingRequest.class);
        assertThat(parent.getStatus()).isEqualTo(SchedulingStatus.BOOKED);          // unchanged
        SchedulingRequest roundAfter = mongoTemplate.findById(round.request.getId(), SchedulingRequest.class);
        assertThat(roundAfter.getStatus()).isEqualTo(SchedulingStatus.SUPERSEDED);  // never counted as committed
        // No new claim inserted for the parent's own (member,start) -> no false slot_taken.
        SlotReservationService.BookingView view = service.viewBooking(b.rawToken, "9.9.9.9");
        assertThat(view.rescheduleRemaining()).isEqualTo(3);                        // cap not consumed
    }

    @Test
    void candidateCancel_removesEvents_releasesClaim_notifiesRecruiter() {   // US3 / SC-011
        when(calendar.cancelBooking(eq(WS), any())).thenReturn(true);
        Seeded b = booked();

        SlotReservationService.CancelResult r = service.cancel(b.rawToken, "9.9.9.9");
        assertThat(r.cleanupIncomplete()).isFalse();

        SchedulingRequest after = mongoTemplate.findById(b.request.getId(), SchedulingRequest.class);
        assertThat(after.getStatus()).isEqualTo(SchedulingStatus.CANCELLED);
        verify(calendar, times(1)).cancelBooking(eq(WS), eq(b.request.getId()));
        assertThat(mongoTemplate.findAll(InterviewSlotClaim.class))
            .allMatch(c -> c.getStatus() == ClaimStatus.RELEASED);
        List<RecruiterNotification> notes = mongoTemplate.findAll(RecruiterNotification.class);
        assertThat(notes).anyMatch(n -> n.getType() == RecruiterNotificationType.INTERVIEW_CANCELLED_BY_CANDIDATE);
    }

    @Test
    void cancel_calendarCleanupFails_marksCleanupIncomplete_andAlertsRecruiter() {   // SC-005 / FR-012
        when(calendar.cancelBooking(eq(WS), any())).thenReturn(false);   // a participant event could not be removed
        Seeded b = booked();

        SlotReservationService.CancelResult r = service.cancel(b.rawToken, "9.9.9.9");
        assertThat(r.cleanupIncomplete()).isTrue();

        SchedulingRequest after = mongoTemplate.findById(b.request.getId(), SchedulingRequest.class);
        assertThat(after.getStatus()).isEqualTo(SchedulingStatus.CLEANUP_INCOMPLETE);   // surfaced, not silent
        List<RecruiterNotification> notes = mongoTemplate.findAll(RecruiterNotification.class);
        assertThat(notes).anyMatch(n -> n.getType() == RecruiterNotificationType.CALENDAR_CLEANUP_INCOMPLETE);
    }

    @Test
    void candidateCancel_replayIsIdempotent() {   // FR-015
        when(calendar.cancelBooking(eq(WS), any())).thenReturn(true);
        Seeded b = booked();
        service.cancel(b.rawToken, "9.9.9.9");
        SlotReservationService.CancelResult second = service.cancel(b.rawToken, "9.9.9.9");
        assertThat(second.cleanupIncomplete()).isFalse();
        verify(calendar, times(1)).cancelBooking(eq(WS), eq(b.request.getId()));    // no 2nd teardown
    }

    @Test
    void capReached_invalidatesLink_andNotifiesRecruiter() {   // FR-005 / SC-007
        free();
        Seeded b = booked();
        // Seed `cap` committed RESCHEDULE rounds in the lineage so the next attempt is over cap.
        for (int i = 0; i < 3; i++) {
            seedCommittedRescheduleRound(b.request.getId());
        }
        assertThatThrownBy(() -> service.openReschedule(b.rawToken, "9.9.9.9"))
            .isInstanceOf(SchedulingExceptions.CapReachedException.class);

        SchedulingRequest after = mongoTemplate.findById(b.request.getId(), SchedulingRequest.class);
        assertThat(after.getManageTokenHash()).isNull();                            // link invalidated
        List<RecruiterNotification> notes = mongoTemplate.findAll(RecruiterNotification.class);
        assertThat(notes).anyMatch(n -> n.getType() == RecruiterNotificationType.RESCHEDULE_CAP_REACHED);
    }

    // --- seed helpers specific to F20 lineage ---

    private Seeded seedRescheduleRound(String parentId, OfferedSlot offered) {
        SchedulingRequest parent = mongoTemplate.findById(parentId, SchedulingRequest.class);
        String raw = com.cadence.security.SecureTokens.newToken();
        Instant now = Instant.now(clock);
        SchedulingRequest round = new SchedulingRequest();
        round.setWorkspaceId(WS);
        round.setCandidateId(parent.getCandidateId());
        round.setTemplateId(parent.getTemplateId());
        round.setStatus(SchedulingStatus.PENDING_SELECTION);
        round.setMode(SchedulingMode.RESCHEDULE);
        round.setParentRequestId(parentId);
        round.setRootRequestId(parent.getRootRequestId() != null ? parent.getRootRequestId() : parentId);
        round.setTokenHash(hasher.hashToken(raw));
        round.setSentAt(now);
        round.setExpiresAt(now.plusSeconds(72 * 3600));
        round.setOfferedSlots(new ArrayList<>(List.of(offered)));
        round.setLocationText("Room 1");
        round.setCreatedAt(now);
        round.setUpdatedAt(now);
        return new Seeded(mongoTemplate.save(round), raw);
    }

    private void seedCommittedRescheduleRound(String parentId) {
        SchedulingRequest parent = mongoTemplate.findById(parentId, SchedulingRequest.class);
        Instant now = Instant.now(clock);
        SchedulingRequest round = new SchedulingRequest();
        round.setWorkspaceId(WS);
        round.setCandidateId(parent.getCandidateId());
        round.setTemplateId(parent.getTemplateId());
        round.setStatus(SchedulingStatus.RESCHEDULED);   // a committed (then superseded) round
        round.setMode(SchedulingMode.RESCHEDULE);
        round.setParentRequestId(parentId);
        round.setRootRequestId(parent.getRootRequestId() != null ? parent.getRootRequestId() : parentId);
        round.setTokenHash(hasher.hashToken(com.cadence.security.SecureTokens.newToken()));
        round.setSentAt(now);
        round.setCreatedAt(now);
        round.setUpdatedAt(now);
        mongoTemplate.save(round);
    }
}
