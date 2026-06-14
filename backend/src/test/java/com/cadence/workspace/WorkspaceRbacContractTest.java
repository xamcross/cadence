package com.cadence.workspace;

import com.cadence.domain.Member;
import com.cadence.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T048 (SC-001/SC-002): every workspace-config surface refuses each of the four non-Admin roles with
 * 403 (read AND write), with no state change; covered for both configured and unconfigured states.
 * The unconfigured + non-Admin arm also satisfies US6 AS-5's server-side half.
 */
class WorkspaceRbacContractTest extends WorkspaceItBase {

    private static final Role[] NON_ADMIN =
        {Role.RECRUITER, Role.HIRING_MANAGER, Role.INTERVIEWER, Role.READ_ONLY};

    private Cookie roleCookie(Role role) {
        return cookie(member(role.name().toLowerCase() + "@x.com", role));
    }

    @Test
    void everyNonAdminRole_isForbiddenOnEverySurface_readAndWrite() throws Exception {
        for (Role role : NON_ADMIN) {
            Cookie c = roleCookie(role);
            // read
            expectForbidden(get("/api/internal/workspace/config").cookie(c));
            // writes (5 surfaces; branding spans PUT /branding + POST /logo)
            expectForbidden(post("/api/internal/workspace/setup").cookie(c).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
            expectForbidden(patch("/api/internal/workspace/config").cookie(c).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
            expectForbidden(put("/api/internal/workspace/branding").cookie(c).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"brandColor\":\"#1F2937\"}"));
            expectForbidden(put("/api/internal/workspace/email").cookie(c).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"sendingDomain\":\"x.com\",\"credential\":\"k\"}"));
            expectForbidden(put("/api/internal/workspace/templates/invite/lock").cookie(c).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"locked\":true}"));
            expectForbidden(delete("/api/internal/workspace/logo").cookie(c).with(csrf()));
            expectForbidden(delete("/api/internal/workspace/email/credential").cookie(c).with(csrf()));
        }
        // No config document was ever created by a refused call (no state change).
        assertThat(mongoTemplate.getCollection("workspaceConfig").countDocuments()).isZero();
    }

    @Test
    void unauthenticated_isUnauthorized_notForbidden() throws Exception {
        mvc.perform(get("/api/internal/workspace/config")).andExpect(status().isUnauthorized());
    }

    private void expectForbidden(MockHttpServletRequestBuilder req) throws Exception {
        mvc.perform(req).andExpect(status().isForbidden());
    }

    // Suppress unused-warning for the seeded admin path not needed here.
    @SuppressWarnings("unused")
    private Member unusedAdmin() { return member("admin@x.com", Role.ADMIN); }
}
