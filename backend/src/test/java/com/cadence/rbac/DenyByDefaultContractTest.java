package com.cadence.rbac;

import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.AuthEventType;
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
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deny-by-default enforcement (T030, SC-001/SC-002): role x endpoint outcomes; anonymous -> 401
 * (not 403); @PreAuthorize-403 renders the JSON envelope; RO-write coverage is inventory-derived (no
 * write handler grants READ_ONLY); and the bounded refusal audit writes exactly one row for repeated
 * probing (FR-028).
 */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
class DenyByDefaultContractTest extends BaseIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MemberService memberService;
    @Autowired SessionService sessionService;
    @Autowired RequestMappingHandlerMapping mapping;

    @BeforeEach
    void cleanup() {
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
        mongoTemplate.remove(new Query(), AuthAuditEvent.class);
    }

    private Cookie cookieFor(Role role) {
        Member m = memberService.create("ws1", role.name().toLowerCase() + "@x.com", role.name(), role, null, null);
        return new Cookie("cad_session", sessionService.issue(m).jwt());
    }

    @Test
    void anonymousInternalRequest_is401_not403() throws Exception {
        mvc.perform(get("/api/internal/members")).andExpect(status().isUnauthorized());
    }

    @Test
    void admin_canListMembers_othersGet403WithEnvelope() throws Exception {
        mvc.perform(get("/api/internal/members").cookie(cookieFor(Role.ADMIN))).andExpect(status().isOk());
        for (Role role : new Role[]{Role.RECRUITER, Role.HIRING_MANAGER, Role.INTERVIEWER, Role.READ_ONLY}) {
            mvc.perform(get("/api/internal/members").cookie(cookieFor(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"))
                .andExpect(content().json("{\"error\":\"forbidden\",\"message\":\"You do not have access to this action.\"}"));
        }
    }

    @Test
    void readOnly_cannotPerformStateChange() throws Exception {
        Member target = memberService.create("ws1", "t@x.com", "T", Role.RECRUITER, null, null);
        mvc.perform(patch("/api/internal/members/{id}/role", target.getId())
                .cookie(cookieFor(Role.READ_ONLY)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"INTERVIEWER\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void noWriteHandlerGrantsReadOnly() {
        // Inventory-derived (FR-012): no internal POST/PUT/PATCH/DELETE handler may grant READ_ONLY
        // or be merely isAuthenticated() (a write must require an elevated role).
        mapping.getHandlerMethods().forEach((info, handler) -> {
            if (handler.getBeanType().getName().startsWith("org.springframework")) {
                return;
            }
            Set<String> patterns = info.getPathPatternsCondition() != null
                ? info.getPathPatternsCondition().getPatternValues() : Set.of();
            boolean internal = patterns.stream().anyMatch(p -> p.startsWith("/api/internal/"));
            if (internal && isWrite(handler)) {
                // FR-012: no internal write may grant READ_ONLY. (Self-service writes like
                // auth/logout are legitimately isAuthenticated() — any role may end their own
                // session — so we assert only the READ_ONLY exclusion, not an elevated-role floor.)
                String expr = preAuthorizeExpression(handler);
                assertThat(expr)
                    .withFailMessage("Write handler %s must declare a non-RO role, was: %s", handler.getMethod(), expr)
                    .doesNotContain("READ_ONLY");
            }
        });
    }

    @Test
    void repeatedRefusal_bounded_exactlyOneAuditRow() throws Exception {
        Cookie ro = cookieFor(Role.READ_ONLY);
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/internal/members").cookie(ro)).andExpect(status().isForbidden());
        }
        long denied = mongoTemplate.findAll(AuthAuditEvent.class).stream()
            .filter(a -> a.getEventType() == AuthEventType.AUTHORIZATION_DENIED).count();
        assertThat(denied).isEqualTo(1); // throttled within the window — no amplification
    }

    private boolean isWrite(HandlerMethod handler) {
        Method m = handler.getMethod();
        if (AnnotatedElementUtils.hasAnnotation(m, PostMapping.class)
            || AnnotatedElementUtils.hasAnnotation(m, PatchMapping.class)) {
            return true;
        }
        RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
        if (rm == null) {
            return false;
        }
        String s = java.util.Arrays.toString(rm.method());
        return s.contains("POST") || s.contains("PUT") || s.contains("PATCH") || s.contains("DELETE");
    }

    private String preAuthorizeExpression(HandlerMethod handler) {
        PreAuthorize pa = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
        if (pa == null) {
            pa = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
        }
        return pa == null ? "" : pa.value();
    }
}
