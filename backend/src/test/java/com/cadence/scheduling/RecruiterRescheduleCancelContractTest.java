package com.cadence.scheduling;

import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.MemberAvailability;
import com.cadence.domain.Member;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.Role;
import com.cadence.domain.SchedulingMode;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.integration.EmailSender;
import com.cadence.service.AvailabilityService;
import com.cadence.service.CalendarEventService;
import com.cadence.service.EmailDispatchService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F20 recruiter reschedule/cancel contract (US2 / US3 AS-2). Role matrix (ADMIN/RECRUITER allowed, others 403),
 * reschedule preserves the booking + recruiter remains uncapped after the candidate cap (SC-007), and recruiter
 * cancel notifies the candidate.
 */
class RecruiterRescheduleCancelContractTest extends SchedulingItBase {

    private static final String REQ_MEMBER = "111111111111111111111111";

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

    private SchedulingRequest booked() {
        configuredWorkspace();
        InterviewTemplate t = seedTemplate(REQ_MEMBER);
        seedContactableCandidate("cand1", "Dana", "dana@x.com");
        OfferedSlot s = slot("0", Instant.parse("2026-06-20T13:00:00Z"),
            Instant.parse("2026-06-20T14:00:00Z"), List.of(REQ_MEMBER), List.of());
        return seedBookedRequest("cand1", t.getId(), "Room 1", s, REQ_MEMBER).request;
    }

    @Test
    void adminReschedule_preservesBooking_returnsInProgress() throws Exception {
        free();
        booked();
        Member admin = member("admin@x.com", Role.ADMIN);
        mvc.perform(post("/api/internal/candidates/{c}/scheduling/reschedule", "cand1").cookie(cookie(admin)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("reschedule_in_progress")));
    }

    @Test
    void interviewerReschedule_isForbidden() throws Exception {
        booked();
        Member iv = member("iv@x.com", Role.INTERVIEWER);
        mvc.perform(post("/api/internal/candidates/{c}/scheduling/reschedule", "cand1").cookie(cookie(iv)).with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    void recruiterReschedule_succeedsEvenWhenCandidateCapReached() throws Exception {   // SC-007
        free();
        SchedulingRequest b = booked();
        for (int i = 0; i < 3; i++) {
            SchedulingRequest round = new SchedulingRequest();
            round.setWorkspaceId(WS);
            round.setCandidateId("cand1");
            round.setTemplateId(b.getTemplateId());
            round.setStatus(SchedulingStatus.RESCHEDULED);
            round.setMode(SchedulingMode.RESCHEDULE);
            round.setParentRequestId(b.getId());
            round.setRootRequestId(b.getId());
            round.setTokenHash(hasher.hashToken(com.cadence.security.SecureTokens.newToken()));
            round.setCreatedAt(Instant.now(clock));
            round.setUpdatedAt(Instant.now(clock));
            mongoTemplate.save(round);
        }
        Member recruiter = member("rec@x.com", Role.RECRUITER);
        mvc.perform(post("/api/internal/candidates/{c}/scheduling/reschedule", "cand1").cookie(cookie(recruiter)).with(csrf()))
            .andExpect(status().isOk())                                   // recruiter is uncapped
            .andExpect(jsonPath("$.status", is("reschedule_in_progress")));
    }

    @Test
    void recruiterCancel_returnsCancelled() throws Exception {
        when(calendar.cancelBooking(eq(WS), any())).thenReturn(true);
        booked();
        Member recruiter = member("rec@x.com", Role.RECRUITER);
        mvc.perform(post("/api/internal/candidates/{c}/scheduling/cancel", "cand1").cookie(cookie(recruiter)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("cancelled")));
    }

    @Test
    void recruiterReschedule_noActiveBooking_returns409() throws Exception {
        configuredWorkspace();
        seedContactableCandidate("cand1", "Dana", "dana@x.com");
        Member recruiter = member("rec@x.com", Role.RECRUITER);
        mvc.perform(post("/api/internal/candidates/{c}/scheduling/reschedule", "cand1").cookie(cookie(recruiter)).with(csrf()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error", is("no_active_booking")));
    }

    @Test
    void recruiterReschedule_candidateInAnotherWorkspace_returnsScopedNotFound() throws Exception {   // FR-025 / SC-014
        booked();   // cand1 booked in WS
        Member otherWsRecruiter = member("ws2", "rec2@x.com", Role.RECRUITER);
        mvc.perform(post("/api/internal/candidates/{c}/scheduling/reschedule", "cand1")
                .cookie(cookie(otherWsRecruiter)).with(csrf()))
            .andExpect(status().isNotFound());   // indistinguishable from a non-existent candidate (no oracle)
    }
}
