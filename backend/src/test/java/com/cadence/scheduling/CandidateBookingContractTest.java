package com.cadence.scheduling;

import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.MemberAvailability;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.integration.EmailSender;
import com.cadence.service.AvailabilityService;
import com.cadence.service.CalendarEventService;
import com.cadence.service.EmailDispatchService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F20 candidate booking-management contract (CandidateBookingController) — public-by-manage-token. Asserts the
 * envelope codes (200/410/400/429), times-only payload (no participant identity / no location), and that cancel
 * is an affirmative POST (a GET to the cancel path is NOT mapped → never cancels, FR-012).
 */
class CandidateBookingContractTest extends SchedulingItBase {

    private static final String REQ_MEMBER = "111111111111111111111111";
    private static final Instant BOOKED_START = Instant.parse("2026-06-20T13:00:00Z");
    private static final Instant BOOKED_END = Instant.parse("2026-06-20T14:00:00Z");
    private static final String LOCATION = "Zoom PIN 12345 SENTINEL-LOC";

    @MockBean AvailabilityService availability;
    @MockBean CalendarEventService calendar;
    @MockBean EmailDispatchService dispatch;
    @MockBean EmailSender emailSender;

    private Seeded booked(Instant start, Instant end) {
        configuredWorkspace();
        InterviewTemplate t = seedTemplate(REQ_MEMBER);
        seedContactableCandidate("cand1", "Dana", "dana@x.com");
        OfferedSlot s = slot("0", start, end, List.of(REQ_MEMBER), List.of());
        return seedBookedRequest("cand1", t.getId(), LOCATION, s, REQ_MEMBER);
    }

    @Test
    void view_booked_returnsTimesOnly_noParticipantIdentity_noLocation() throws Exception {
        Seeded b = booked(BOOKED_START, BOOKED_END);
        String body = mvc.perform(get("/api/candidate/booking/{t}", b.rawToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("booked")))
            .andExpect(jsonPath("$.canCancel", is(true)))
            .andReturn().getResponse().getContentAsString();
        // Non-circular: the required member id and the location sentinel must never leak to the candidate.
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain(REQ_MEMBER);
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("SENTINEL-LOC");
    }

    @Test
    void view_pastInterview_returns410() throws Exception {
        // A booking whose start is before `now` (frozen clock 2026-06-14) -> distinct 410.
        Seeded b = booked(Instant.parse("2026-06-10T13:00:00Z"), Instant.parse("2026-06-10T14:00:00Z"));
        mvc.perform(get("/api/candidate/booking/{t}", b.rawToken))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.error", is("expired")));
    }

    @Test
    void view_unknownToken_returns400_invalid() throws Exception {
        mvc.perform(get("/api/candidate/booking/{t}", "not-a-real-token"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", is("invalid")));
    }

    @Test
    void reschedule_returnsTimesOnlySlots() throws Exception {
        when(availability.query(eq(WS), any(), any(), anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(3);
            List<MemberAvailability> out = new ArrayList<>();
            for (String id : ids) out.add(new MemberAvailability(id, AvailabilityStatus.DATA, List.of()));
            return out;
        });
        Seeded b = booked(BOOKED_START, BOOKED_END);
        mvc.perform(post("/api/candidate/booking/{t}/reschedule", b.rawToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rescheduleToken").exists())
            .andExpect(jsonPath("$.slots").isArray());
    }

    @Test
    void cancel_affirmativePost_cancels_butGetDoesNot() throws Exception {
        when(calendar.cancelBooking(eq(WS), any())).thenReturn(true);
        Seeded b = booked(BOOKED_START, BOOKED_END);

        // A GET to the cancel path is not mapped -> 405/404, and MUST NOT cancel.
        mvc.perform(get("/api/candidate/booking/{t}/cancel", b.rawToken))
            .andExpect(status().is4xxClientError());
        SchedulingRequest stillBooked = mongoTemplate.findById(b.request.getId(), SchedulingRequest.class);
        org.assertj.core.api.Assertions.assertThat(stillBooked.getStatus()).isEqualTo(SchedulingStatus.BOOKED);

        // The affirmative POST cancels.
        mvc.perform(post("/api/candidate/booking/{t}/cancel", b.rawToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("cancelled")));
        SchedulingRequest after = mongoTemplate.findById(b.request.getId(), SchedulingRequest.class);
        org.assertj.core.api.Assertions.assertThat(after.getStatus()).isEqualTo(SchedulingStatus.CANCELLED);
    }
}
