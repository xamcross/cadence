package com.cadence.pipeline;

import com.cadence.domain.CandidateEventType;
import com.cadence.domain.CandidateStatusOutcome;
import com.cadence.domain.ErasureState;
import com.cadence.domain.Member;
import com.cadence.domain.RequisitionStatus;
import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F51 T041 / FR-021..FR-023 / SC-007: the candidate timeline — chronological PII-free events (incl. the email-sent
 * event, so SC-007 is non-vacuous), feedback-pending, HM scoping with no-oracle 404, and no scorecard free-text.
 */
class PipelineTimelineIT extends PipelineItBase {

    @Test
    void chronological_withEmailSent_andFeedbackPending_noScorecardPayload() throws Exception {
        configuredWorkspace();
        seedActive("c1", "Ada", 1, null);
        // Seeded OUT of chronological order so the assertion proves the finder sorts (not the seed order).
        seedAudit("c1", CandidateEventType.SCORECARD_SUBMITTED, NOW.minus(Duration.ofDays(1)));
        seedAudit("c1", CandidateEventType.RECORD_CREATED, NOW.minus(Duration.ofDays(4)));
        seedAudit("c1", CandidateEventType.BOOKING_CHANGED, NOW.minus(Duration.ofDays(2)));
        seedAudit("c1", CandidateEventType.MESSAGE_SENT, NOW.minus(Duration.ofDays(3)));
        seedFeedbackPending("c1", "SENTINEL_SCORECARD_SECRET");

        var rec = member("rec@x.test", Role.RECRUITER);
        String resp = mvc.perform(get("/api/internal/pipeline/candidates/{c}/timeline", "c1").cookie(cookie(rec)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.events.length()").value(4))
            .andExpect(jsonPath("$.events[0].type").value("RECORD_CREATED"))
            .andExpect(jsonPath("$.events[1].type").value("MESSAGE_SENT"))
            .andExpect(jsonPath("$.events[3].type").value("SCORECARD_SUBMITTED"))
            .andExpect(jsonPath("$.feedbackPending").value(true))
            .andReturn().getResponse().getContentAsString();
        assertThat(resp).doesNotContain("SENTINEL_SCORECARD_SECRET");   // no scorecard free-text leaks (FR-022)
    }

    @Test
    void emptyTimeline_200() throws Exception {
        configuredWorkspace();
        seedActive("c1", "Ada", 1, null);
        var rec = member("rec@x.test", Role.RECRUITER);
        mvc.perform(get("/api/internal/pipeline/candidates/{c}/timeline", "c1").cookie(cookie(rec)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.events.length()").value(0));
    }

    @Test
    void erasedCandidate_404() throws Exception {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "S", NOW, CandidateStatusOutcome.IN_PROGRESS, ErasureState.ERASED, null);
        var rec = member("rec@x.test", Role.RECRUITER);
        mvc.perform(get("/api/internal/pipeline/candidates/{c}/timeline", "c1").cookie(cookie(rec)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void hmScoping_ownCandidate200_outOfScope404() throws Exception {
        configuredWorkspace();
        seedRequisition("r1", "R1", RequisitionStatus.OPEN);
        seedRequisition("r2", "R2", RequisitionStatus.OPEN);
        Member hm = member("hm@x.test", Role.HIRING_MANAGER);
        seedAssignment(hm.getId(), "r1");
        seedActive("mine", "Ada", 1, "r1");
        seedActive("theirs", "Bea", 1, "r2");

        mvc.perform(get("/api/internal/pipeline/candidates/{c}/timeline", "mine").cookie(cookie(hm)))
            .andExpect(status().isOk());
        mvc.perform(get("/api/internal/pipeline/candidates/{c}/timeline", "theirs").cookie(cookie(hm)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"));
    }
}
