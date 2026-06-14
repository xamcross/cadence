package com.cadence.rbac;

import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.domain.Session;
import com.cadence.repository.MemberRepository;
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
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SC-014 (T023): a non-canonical role value is rejected with 400 invalid_role and no persisted change. */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
class RoleValidationContractTest extends BaseIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MemberService memberService;
    @Autowired MemberRepository members;
    @Autowired SessionService sessionService;

    @BeforeEach
    void cleanup() {
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
    }

    @Test
    void nonCanonicalRole_isRejected_noChange() throws Exception {
        Member admin = memberService.create("ws1", "admin@x.com", "Admin", Role.ADMIN, null, null);
        Member target = memberService.create("ws1", "t@x.com", "T", Role.RECRUITER, null, null);
        Cookie adminCookie = new Cookie("cad_session", sessionService.issue(admin).jwt());

        mvc.perform(patch("/api/internal/members/{id}/role", target.getId())
                .cookie(adminCookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"SUPERADMIN\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_role"));

        assertThat(members.findById(target.getId()).orElseThrow().getRole()).isEqualTo(Role.RECRUITER);
    }
}
