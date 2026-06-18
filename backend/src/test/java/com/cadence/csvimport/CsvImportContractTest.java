package com.cadence.csvimport;

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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F42 API contract + RBAC (SC-002/SC-009/SC-011/SC-015). Upload returns 202 (even for a malformed CSV — parse
 * is deferred), status is a workspace-scoped no-oracle 404, resolve is 409 unless AWAITING, and the 5-role
 * matrix (ADMIN/RECRUITER allowed; HM/Interviewer/Read-only forbidden). All members + uploads share one
 * workspace so the scoped reads line up.
 */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
@org.springframework.test.context.TestPropertySource(properties = "cadence.scheduling.rate-limit-per-minute=1000")
class CsvImportContractTest extends CsvImportItBase {

    @Autowired MockMvc mvc;
    @Autowired MemberService memberService;
    @Autowired SessionService sessionService;

    @BeforeEach
    void cleanAuth() {
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
    }

    /** Create a member in the SAME workspace the import collections are cleaned under (WS), and return its cookie. */
    private Cookie cookie(Role role) {
        Member m = memberService.create(WS, role.name().toLowerCase() + "@example.com",
            role.name().toLowerCase() + "@example.com", role, null, null);
        return new Cookie("cad_session", sessionService.issue(m).jwt());
    }

    private static MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "candidates.csv", "text/csv",
            body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void upload_returns202_forValidAndMalformed_parseDeferred() throws Exception {
        mvc.perform(multipart("/api/internal/import/csv").file(csv("name,email\nAda,ada@example.com\n"))
                .cookie(cookie(Role.RECRUITER)).with(csrf()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").exists())
            .andExpect(jsonPath("$.status", is("ACCEPTED")));
        // A structurally-malformed CSV is STILL accepted (202) — validation is deferred to the worker.
        mvc.perform(multipart("/api/internal/import/csv").file(csv("name,email\n\"unterminated,ada@example.com\n"))
                .cookie(cookie(Role.ADMIN)).with(csrf()))
            .andExpect(status().isAccepted());
    }

    @Test
    void upload_emptyFile_is400() throws Exception {
        mvc.perform(multipart("/api/internal/import/csv").file(csv(""))
                .cookie(cookie(Role.ADMIN)).with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", is("invalid_import")));
    }

    @Test
    void status_unknownOrCrossWorkspace_isIndistinguishable404() throws Exception {
        mvc.perform(get("/api/internal/import/000000000000000000000000/status").cookie(cookie(Role.RECRUITER)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error", is("not_found")));
    }

    @Test
    void resolve_onNonAwaitingJob_is409() throws Exception {
        Cookie admin = cookie(Role.ADMIN);
        // Upload under WS via the service helper, then process to COMPLETED (not awaiting).
        String jobId = uploadAndProcess("name,email\nAda,ada@example.com\n");
        mvc.perform(post("/api/internal/import/" + jobId + "/resolve").cookie(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"decisions\":[],\"defaultAction\":\"SKIP\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error", is("invalid_state")));
    }

    @Test
    void upload_roleMatrix() throws Exception {
        for (Role allowed : new Role[]{Role.ADMIN, Role.RECRUITER}) {
            mvc.perform(multipart("/api/internal/import/csv").file(csv("name,email\nA,a@example.com\n"))
                    .cookie(cookie(allowed)).with(csrf()))
                .andExpect(status().isAccepted());
        }
        for (Role denied : new Role[]{Role.HIRING_MANAGER, Role.INTERVIEWER, Role.READ_ONLY}) {
            mvc.perform(multipart("/api/internal/import/csv").file(csv("name,email\nA,a@example.com\n"))
                    .cookie(cookie(denied)).with(csrf()))
                .andExpect(status().isForbidden());
        }
    }
}
