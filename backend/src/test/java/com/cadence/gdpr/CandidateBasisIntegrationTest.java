package com.cadence.gdpr;

import com.cadence.domain.Candidate;
import com.cadence.service.ContactPermissionGate;
import com.cadence.service.ContactPermissionGate.Reason;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T025 / US1 / SC-013: record -> permit, withdraw -> deny, re-record -> permit. */
class CandidateBasisIntegrationTest extends GdprItBase {

    @Autowired ContactPermissionGate gate;

    @Test
    void recordWithdrawReRecord_flipsTheGate() throws Exception {
        Candidate c = seedCandidate("Frank", "frank@example.com", "+15550000010");
        Cookie admin = adminCookie();

        // No basis on create -> gate denies no_basis.
        assertThat(gate.evaluate(WS, c.getId()).reason()).isEqualTo(Reason.NO_BASIS);

        // Record -> permit.
        put("/api/internal/candidates/{id}/basis", c.getId());
        mvc.perform(put("/api/internal/candidates/{id}/basis", c.getId()).cookie(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"lawfulBasis\":\"LEGITIMATE_INTEREST\"}"))
            .andExpect(status().isOk());
        assertThat(gate.evaluate(WS, c.getId()).permit()).isTrue();

        // Withdraw -> deny withdrawn.
        mvc.perform(delete("/api/internal/candidates/{id}/basis", c.getId()).cookie(admin).with(csrf()))
            .andExpect(status().isOk());
        assertThat(gate.evaluate(WS, c.getId()).reason()).isEqualTo(Reason.WITHDRAWN);

        // Re-record -> permit again.
        mvc.perform(put("/api/internal/candidates/{id}/basis", c.getId()).cookie(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"lawfulBasis\":\"CONSENT\"}"))
            .andExpect(status().isOk());
        assertThat(gate.evaluate(WS, c.getId()).permit()).isTrue();
    }

    @Test
    void recruiter_mayRecordBasis() throws Exception {
        Candidate c = seedCandidate("Grace", "grace@example.com", "+15550000011");
        Cookie recruiter = cookie(member("recruiter@x.com", com.cadence.domain.Role.RECRUITER));
        mvc.perform(put("/api/internal/candidates/{id}/basis", c.getId()).cookie(recruiter).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"lawfulBasis\":\"CONSENT\"}"))
            .andExpect(status().isOk());
        assertThat(gate.evaluate(WS, c.getId()).permit()).isTrue();
    }

    @Test
    void unknownBasis_isRejected400() throws Exception {
        Candidate c = seedCandidate("Heidi", "heidi@example.com", "+15550000012");
        mvc.perform(put("/api/internal/candidates/{id}/basis", c.getId()).cookie(adminCookie()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"lawfulBasis\":\"BOGUS\"}"))
            .andExpect(status().isBadRequest());
    }
}
