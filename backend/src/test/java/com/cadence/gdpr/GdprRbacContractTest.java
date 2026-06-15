package com.cadence.gdpr;

import com.cadence.domain.Candidate;
import com.cadence.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T051 / SC-004: the surface x role matrix. CSRF on all mutating calls so 403s are role-denials. */
class GdprRbacContractTest extends GdprItBase {

    @Test
    void erasureAndBasis_allowAdminAndRecruiter_denyOthers() throws Exception {
        Candidate c = seedCandidate("Q", "q@example.com", "+15550000040");
        // Allowed roles succeed.
        for (Role r : new Role[]{Role.ADMIN, Role.RECRUITER}) {
            Cookie cookie = cookie(member(r.name().toLowerCase() + "@x.com", r));
            mvc.perform(put("/api/internal/candidates/{id}/basis", c.getId()).cookie(cookie).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"lawfulBasis\":\"CONSENT\"}"))
                .andExpect(status().isOk());
        }
        // Disallowed roles forbidden.
        for (Role r : new Role[]{Role.HIRING_MANAGER, Role.INTERVIEWER, Role.READ_ONLY}) {
            Cookie cookie = cookie(member(r.name().toLowerCase() + "@x.com", r));
            expectForbidden(post("/api/internal/candidates/{id}/erasure", c.getId()).cookie(cookie).with(csrf()));
            expectForbidden(put("/api/internal/candidates/{id}/basis", c.getId()).cookie(cookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"lawfulBasis\":\"CONSENT\"}"));
        }
    }

    @Test
    void adminOnlySurfaces_denyEveryNonAdmin() throws Exception {
        Candidate c = seedCandidate("R", "r@example.com", "+15550000041");
        for (Role r : new Role[]{Role.RECRUITER, Role.HIRING_MANAGER, Role.INTERVIEWER, Role.READ_ONLY}) {
            Cookie cookie = cookie(member(r.name().toLowerCase() + "@x.com", r));
            expectForbidden(get("/api/internal/candidates/{id}/audit", c.getId()).cookie(cookie));
            expectForbidden(get("/api/internal/erasure-requests").cookie(cookie));
            expectForbidden(post("/api/internal/erasure-requests/{id}/confirm", "x").cookie(cookie).with(csrf()));
            expectForbidden(get("/api/internal/retention/flagged").cookie(cookie));
            expectForbidden(post("/api/internal/retention/{id}/delete", c.getId()).cookie(cookie).with(csrf()));
        }
    }

    @Test
    void unauthenticated_isUnauthorized() throws Exception {
        mvc.perform(get("/api/internal/erasure-requests")).andExpect(status().isUnauthorized());
    }

    private void expectForbidden(MockHttpServletRequestBuilder req) throws Exception {
        mvc.perform(req).andExpect(status().isForbidden());
    }
}
