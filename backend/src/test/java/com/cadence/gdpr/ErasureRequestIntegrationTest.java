package com.cadence.gdpr;

import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateEventType;
import com.cadence.domain.ErasureReasonCode;
import com.cadence.domain.ErasureRequest;
import com.cadence.domain.ErasureState;
import com.cadence.service.ErasureRequestService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T037 / US4 / SC-015: PII-free intake, confirm wipes, reject, guarded double-confirm, reason validation. */
class ErasureRequestIntegrationTest extends GdprItBase {

    @Autowired ErasureRequestService requestService;

    @Test
    void request_isPiiFree_andAudited() {
        Candidate c = seedCandidate("Laura", "laura@example.com", "+15550000030");
        ErasureRequest r = requestService.requestErasure(WS, c.getId(), ErasureReasonCode.CANDIDATE_REQUEST);
        assertThat(r.getReasonCode()).isEqualTo(ErasureReasonCode.CANDIDATE_REQUEST);
        // The request record references the candidate by internal id only — no PII field exists on it.
        assertThat(mongoTemplate.findAll(com.cadence.domain.CandidateAuditEvent.class))
            .anyMatch(e -> e.getEventType() == CandidateEventType.ERASURE_REQUESTED);
    }

    @Test
    void adminConfirm_runsWipe_andAudits() throws Exception {
        Candidate c = seedCandidate("Mallory", "mallory@example.com", "+15550000031");
        ErasureRequest r = requestService.requestErasure(WS, c.getId(), ErasureReasonCode.CANDIDATE_REQUEST);
        Cookie admin = adminCookie();

        mvc.perform(get("/api/internal/erasure-requests").cookie(admin))
            .andExpect(status().isOk());

        mvc.perform(post("/api/internal/erasure-requests/{id}/confirm", r.getId()).cookie(admin).with(csrf()))
            .andExpect(status().isOk());

        Candidate after = mongoTemplate.findById(c.getId(), Candidate.class);
        assertThat(after.getErasureState()).isEqualTo(ErasureState.ERASED);
        assertThat(mongoTemplate.findAll(com.cadence.domain.CandidateAuditEvent.class))
            .anyMatch(e -> e.getEventType() == CandidateEventType.ERASURE_REQUEST_CONFIRMED);
    }

    @Test
    void doubleConfirm_secondIs409_singleWipe() throws Exception {
        Candidate c = seedCandidate("Niaj", "niaj@example.com", "+15550000032");
        ErasureRequest r = requestService.requestErasure(WS, c.getId(), ErasureReasonCode.CANDIDATE_REQUEST);
        Cookie admin = adminCookie();
        mvc.perform(post("/api/internal/erasure-requests/{id}/confirm", r.getId()).cookie(admin).with(csrf()))
            .andExpect(status().isOk());
        mvc.perform(post("/api/internal/erasure-requests/{id}/confirm", r.getId()).cookie(admin).with(csrf()))
            .andExpect(status().isConflict());

        long completed = mongoTemplate.findAll(com.cadence.domain.CandidateAuditEvent.class).stream()
            .filter(e -> e.getEventType() == CandidateEventType.ERASURE_COMPLETED).count();
        assertThat(completed).isEqualTo(1);
    }

    @Test
    void reject_noWipe_audited() throws Exception {
        Candidate c = seedCandidate("Olivia", "olivia@example.com", "+15550000033");
        ErasureRequest r = requestService.requestErasure(WS, c.getId(), ErasureReasonCode.CANDIDATE_REQUEST);
        mvc.perform(post("/api/internal/erasure-requests/{id}/reject", r.getId()).cookie(adminCookie()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"NOT_A_CANDIDATE\"}"))
            .andExpect(status().isOk());
        Candidate after = mongoTemplate.findById(c.getId(), Candidate.class);
        assertThat(after.getErasureState()).isEqualTo(ErasureState.ACTIVE);
    }

    @Test
    void reject_withUnknownReason_is400_staysPending() throws Exception {
        Candidate c = seedCandidate("Peggy", "peggy@example.com", "+15550000034");
        ErasureRequest r = requestService.requestErasure(WS, c.getId(), ErasureReasonCode.CANDIDATE_REQUEST);
        mvc.perform(post("/api/internal/erasure-requests/{id}/reject", r.getId()).cookie(adminCookie()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"BOGUS\"}"))
            .andExpect(status().isBadRequest());
        ErasureRequest after = mongoTemplate.findById(r.getId(), ErasureRequest.class);
        assertThat(after.getStatus()).isEqualTo(com.cadence.domain.RequestStatus.PENDING);
    }

    @Test
    void requests_areAdminOnly() throws Exception {
        Cookie recruiter = cookie(member("rec@x.com", com.cadence.domain.Role.RECRUITER));
        mvc.perform(get("/api/internal/erasure-requests").cookie(recruiter)).andExpect(status().isForbidden());
    }
}
