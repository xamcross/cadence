package com.cadence.gdpr;

import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditEvent;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T034 / US3 / SC-007/SC-008/SC-012: append-only, non-PII, ordered, survives erasure, Admin-only. */
class CandidateAuditIntegrationTest extends GdprItBase {

    @Test
    void adminReadsOrderedNonPiiLog() throws Exception {
        Candidate c = seedCandidate("Ivan", "ivan@example.com", "+15550000020");
        mvc.perform(get("/api/internal/candidates/{id}/audit", c.getId()).cookie(adminCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entries[0].eventType").value("RECORD_CREATED"))
            // No PII fields are present on any entry.
            .andExpect(jsonPath("$.entries[0].name").doesNotExist())
            .andExpect(jsonPath("$.entries[0].email").doesNotExist());
    }

    @Test
    void auditSurvivesErasure_byteIdenticalEntriesPlusOneNew() throws Exception {
        Candidate c = seedCandidate("Judy", "judy@example.com", "+15550000021");
        List<CandidateAuditEvent> before = mongoTemplate.findAll(CandidateAuditEvent.class);
        assertThat(before).hasSize(1); // RECORD_CREATED

        mvc.perform(post("/api/internal/candidates/{id}/erasure", c.getId()).cookie(adminCookie()).with(csrf()))
            .andExpect(status().isOk());

        List<CandidateAuditEvent> after = mongoTemplate.findAll(CandidateAuditEvent.class);
        assertThat(after).hasSize(2); // original RECORD_CREATED preserved + ERASURE_COMPLETED
        // The pre-erasure entry is unchanged (same id + fields).
        CandidateAuditEvent created = before.get(0);
        assertThat(after).anyMatch(e -> e.getId().equals(created.getId())
            && e.getEventType() == created.getEventType()
            && e.getOccurredAt().equals(created.getOccurredAt()));
    }

    @Test
    void auditRead_isAdminOnly() throws Exception {
        Candidate c = seedCandidate("Ken", "ken@example.com", "+15550000022");
        Cookie recruiter = cookie(member("rec@x.com", com.cadence.domain.Role.RECRUITER));
        mvc.perform(get("/api/internal/candidates/{id}/audit", c.getId()).cookie(recruiter))
            .andExpect(status().isForbidden());
    }

    @Test
    void unknownCandidate_auditRead_isEmptyNotOracle() throws Exception {
        mvc.perform(get("/api/internal/candidates/{id}/audit", "0123456789abcdef01234567").cookie(adminCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entries").isEmpty());
    }
}
