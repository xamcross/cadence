package com.cadence.scheduling;

import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.BusyInterval;
import com.cadence.domain.ClaimStatus;
import com.cadence.domain.InterviewSlotClaim;
import com.cadence.domain.MemberAvailability;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.PanelBookingResult;
import com.cadence.domain.PanelBookingResult.MemberEventResult;
import com.cadence.domain.PanelBookingResult.MemberOutcome;
import com.cadence.domain.PanelBookingResult.PanelOutcome;
import com.cadence.domain.SchedulingRequest;
import com.cadence.integration.EmailSender;
import com.cadence.service.AvailabilityService;
import com.cadence.service.CalendarEventService;
import com.cadence.service.EmailDispatchService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F13 confirm-endpoint HTTP contract (review loop-1 NIT 4): drives the exception->envelope wiring through
 * {@code CandidateSchedulingController} + {@code SchedulingExceptionHandler} over MockMvc for every confirm
 * verb — 200 book, 200 idempotent replay, 409 slot_taken, 409 slot_no_longer_available, 410 expired, 400
 * missing slotId. The service-layer branches are covered by SlotReservationConfirmTest; this closes the
 * controller/advice mapping. CalendarEventService + email paths are mocked for determinism.
 */
class CandidateSlotConfirmContractTest extends SchedulingItBase {

    private static final String REQ_MEMBER = "111111111111111111111111";
    private static final Instant START = Instant.parse("2026-06-20T13:00:00Z");
    private static final Instant END = Instant.parse("2026-06-20T14:00:00Z");

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

    private void panel(PanelOutcome outcome) {
        when(calendar.createPanelEvents(eq(WS), any(), anyList(), any())).thenReturn(
            new PanelBookingResult(outcome, List.of(new MemberEventResult(REQ_MEMBER, MemberOutcome.CREATED, "e1"))));
    }

    private Seeded ready() {
        seedTemplate(REQ_MEMBER);
        seedContactableCandidate("cand1", "Dana", "dana@x.com");
        OfferedSlot s = slot("0", START, END, List.of(REQ_MEMBER), List.of());
        return seedPendingRequest("cand1", seedTemplateId(), "Room 7", List.of(s));
    }

    private String seedTemplateId() {
        return mongoTemplate.findOne(new Query(), com.cadence.domain.InterviewTemplate.class).getId();
    }

    private org.springframework.test.web.servlet.ResultActions confirm(String token, String slotIdJson) throws Exception {
        return mvc.perform(post("/api/candidate/scheduling/{token}/confirm", token)
            .contentType(MediaType.APPLICATION_JSON).content(slotIdJson));
    }

    @Test
    void confirm_booksAndIsIdempotent() throws Exception {
        freeAtConfirm();
        panel(PanelOutcome.CREATED);
        Seeded s = ready();
        confirm(s.rawToken, "{\"slotId\":\"0\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("booked"));
        // Replay: still 200 booked, no error.
        confirm(s.rawToken, "{\"slotId\":\"0\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("booked"));
    }

    @Test
    void confirm_slotTaken_returns409() throws Exception {
        freeAtConfirm();
        panel(PanelOutcome.CREATED);
        Seeded s = ready();
        // Pre-claim the interviewer-time from a different request so the confirm's claim insert collides.
        InterviewSlotClaim c = new InterviewSlotClaim(WS, REQ_MEMBER, START, "other-req", Instant.now(clock));
        mongoTemplate.insert(c);
        confirm(s.rawToken, "{\"slotId\":\"0\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("slot_taken"));
    }

    @Test
    void confirm_staleSlot_returns409() throws Exception {
        // Required member busy at confirm -> StaleSlot.
        when(availability.query(eq(WS), any(), any(), anyList())).thenReturn(
            List.of(new MemberAvailability(REQ_MEMBER, AvailabilityStatus.DATA, List.of(new BusyInterval(START, END)))));
        panel(PanelOutcome.CREATED);
        Seeded s = ready();
        confirm(s.rawToken, "{\"slotId\":\"0\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("slot_no_longer_available"));
    }

    @Test
    void confirm_expiredToken_returns410() throws Exception {
        freeAtConfirm();
        Seeded s = ready();
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(s.request.getId())),
            new Update().set("expiresAt", Instant.now(clock).minusSeconds(60)), SchedulingRequest.class);
        confirm(s.rawToken, "{\"slotId\":\"0\"}")
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.error").value("expired"));
    }

    @Test
    void confirm_missingSlotId_returns400() throws Exception {
        Seeded s = ready();
        confirm(s.rawToken, "{}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_request"));
    }
}
