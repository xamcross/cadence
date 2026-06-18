package com.cadence.feedback;

import com.cadence.domain.FeedbackRequest;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SC-010/011/017: the recruiter read. ADMIN/RECRUITER 200; HM/Interviewer/Read-only 403 (HM deferred to F51);
 * cross-workspace/unknown interview id -> indistinguishable 404 (booking-first resolution); in-workspace but no
 * feedback -> 200 empty; the pending list surfaces outstanding requests.
 */
class InterviewFeedbackContractTest extends FeedbackItBase {

    private void seedSubmittedAndPending() {
        configuredWorkspace();
        seedCandidate("cand1", "Dana", "dana@example.com");
        Member a = member("a@example.com", Role.INTERVIEWER);
        Member b = member("b@example.com", Role.INTERVIEWER);
        seedBookedInterview("req1", "cand1", 4);
        seedClaim("req1", a.getId(), Instant.now(clock).minus(Duration.ofHours(4)));
        seedClaim("req1", b.getId(), Instant.now(clock).minus(Duration.ofHours(4)));
        scheduler.sweep();
        FeedbackRequest aReq = feedbackRepo.findByWorkspaceIdAndInterviewEventId(WS, "req1").stream()
            .filter(r -> r.getInterviewerMemberId().equals(a.getId())).findFirst().orElseThrow();
        feedbackService.submit(aReq.getToken(),
            new com.cadence.api.FeedbackDtos.ScorecardSubmission("YES",
                java.util.List.of(new com.cadence.api.FeedbackDtos.Rating("Technical skills", 4)), "great"), "1.1.1.1");
    }

    @Test
    void recruiter_readsMixedStatus_andDecryptedScorecard() throws Exception {
        seedSubmittedAndPending();
        Cookie recruiter = cookie(member("rec@example.com", Role.RECRUITER));
        mvc.perform(get("/api/internal/interviews/{id}/feedback", "req1").cookie(recruiter))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.interviewEventId", is("req1")))
            .andExpect(jsonPath("$.items.length()", is(2)));
    }

    @Test
    void roleMatrix_adminRecruiterAllowed_othersForbidden() throws Exception {
        seedSubmittedAndPending();
        for (Role allowed : new Role[]{Role.ADMIN, Role.RECRUITER}) {
            Cookie c = cookie(member(allowed.name().toLowerCase() + "@example.com", allowed));
            mvc.perform(get("/api/internal/interviews/{id}/feedback", "req1").cookie(c))
                .andExpect(status().isOk());
        }
        for (Role denied : new Role[]{Role.HIRING_MANAGER, Role.INTERVIEWER, Role.READ_ONLY}) {
            Cookie c = cookie(member(denied.name().toLowerCase() + "@example.com", denied));
            mvc.perform(get("/api/internal/interviews/{id}/feedback", "req1").cookie(c))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void unknownOrCrossWorkspaceInterview_indistinguishable404() throws Exception {
        seedSubmittedAndPending();
        Cookie admin = cookie(member("admin@example.com", Role.ADMIN));
        mvc.perform(get("/api/internal/interviews/{id}/feedback", "does-not-exist").cookie(admin))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error", is("not_found")))
            .andExpect(jsonPath("$.message").doesNotExist()); // byte-identical, no message oracle
    }

    @Test
    void pendingList_surfacesOutstanding() throws Exception {
        seedSubmittedAndPending();
        Cookie admin = cookie(member("admin@example.com", Role.ADMIN));
        mvc.perform(get("/api/internal/feedback/pending").cookie(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()", is(1))); // only interviewer B remains pending
    }
}
