package com.cadence.ats;

import com.cadence.auth.AuthTestConfig;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.domain.Session;
import com.cadence.service.MemberService;
import com.cadence.service.SessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F41 FR-004: the provider-parameterized ATS endpoints enforce the role matrix — ADMIN mutates, ADMIN/RECRUITER
 * read, HM/Interviewer/Read-only are forbidden — plus the unknown-{provider} -> 400 no-oracle case and the 409
 * verification_failed envelope. (No F40 controller contract test existed; this is net-new.)
 */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
class AtsConnectionContractTest extends AtsItBase {

    private static final String WS = "ws-contract";

    @Autowired protected MockMvc mvc;
    @Autowired protected MemberService memberService;
    @Autowired protected SessionService sessionService;

    @BeforeEach
    void cleanAuth() {
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
    }

    private Cookie cookie(Role role) {
        Member m = memberService.create(WS, role.name().toLowerCase() + "@example.com",
            role.name().toLowerCase() + "@example.com", role, null, null);
        return new Cookie("cad_session", sessionService.issue(m).jwt());
    }

    @Test
    void listConnections_adminAndRecruiterAllowed_othersForbidden() throws Exception {
        for (Role allowed : new Role[]{Role.ADMIN, Role.RECRUITER}) {
            mvc.perform(get("/api/internal/ats/connections").cookie(cookie(allowed)))
                .andExpect(status().isOk())
                // FR-004/FR-003: the health surface NEVER carries the secret — only credentialSet.
                .andExpect(jsonPath("$[0].apiKey").doesNotExist())
                .andExpect(jsonPath("$[0].credentialSet").exists());
        }
        for (Role denied : new Role[]{Role.HIRING_MANAGER, Role.INTERVIEWER, Role.READ_ONLY}) {
            mvc.perform(get("/api/internal/ats/connections").cookie(cookie(denied)))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void connectAndDisconnect_adminOnly() throws Exception {
        seedTeamEntitlement(WS); // 032 T7: connect() gates on ATS_INTEGRATIONS before the credential check
        Cookie admin = cookie(Role.ADMIN);
        Cookie recruiter = cookie(Role.RECRUITER);
        // ADMIN can mutate (POST verifies against the Lever stub -> 200).
        mvc.perform(post("/api/internal/ats/LEVER/connection").cookie(admin).with(csrf())
                .contentType("application/json").content("{\"apiKey\":\"k\"}"))
            .andExpect(status().isOk());
        mvc.perform(delete("/api/internal/ats/LEVER/connection").cookie(admin).with(csrf()))
            .andExpect(status().isNoContent());
        // RECRUITER (read-only) cannot mutate.
        mvc.perform(post("/api/internal/ats/LEVER/connection").cookie(recruiter).with(csrf())
                .contentType("application/json").content("{\"apiKey\":\"k\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void unknownProvider_is400NotOracle() throws Exception {
        mvc.perform(get("/api/internal/ats/NOTAPROVIDER/connection").cookie(cookie(Role.ADMIN)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", is("invalid_request")));
    }

    @Test
    void rejectedCredential_is409VerificationFailed() throws Exception {
        seedTeamEntitlement(WS); // 032 T7: connect() gates on ATS_INTEGRATIONS before the credential check
        leverStub.program("GET", "/v1/opportunities", 401);
        mvc.perform(post("/api/internal/ats/LEVER/connection").cookie(cookie(Role.ADMIN)).with(csrf())
                .contentType("application/json").content("{\"apiKey\":\"bad\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error", is("verification_failed")));
    }
}
