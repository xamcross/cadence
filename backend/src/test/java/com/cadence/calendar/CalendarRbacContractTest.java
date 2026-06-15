package com.cadence.calendar;

import com.cadence.domain.CalendarProvider;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SC-007: every role manages only its OWN connection; cross-member isolation; 401; no-store; 400. */
class CalendarRbacContractTest extends CalendarItBase {

    private static final Role[] ALL = {Role.ADMIN, Role.RECRUITER, Role.HIRING_MANAGER, Role.INTERVIEWER, Role.READ_ONLY};

    @Test
    void everyAuthenticatedRole_canManageOwnConnections() throws Exception {
        for (Role r : ALL) {
            Cookie cookie = cookie(member(r.name().toLowerCase() + "@x.com", r));
            mvc.perform(get("/api/internal/calendar/connections").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")); // Security #10
            mvc.perform(post("/api/internal/calendar/connections/google/start").cookie(cookie).with(csrf()))
                .andExpect(status().isOk());
            mvc.perform(delete("/api/internal/calendar/connections/google").cookie(cookie).with(csrf()))
                .andExpect(status().isNoContent());
        }
    }

    @Test
    void unauthenticated_isUnauthorized() throws Exception {
        mvc.perform(get("/api/internal/calendar/connections")).andExpect(status().isUnauthorized());
    }

    @Test
    void members_seeOnlyTheirOwnConnections() throws Exception {
        Member a = member("a@x.com", Role.RECRUITER);
        Member b = member("b@x.com", Role.RECRUITER);
        connect(a, CalendarProvider.GOOGLE, "a@example.com");

        mvc.perform(get("/api/internal/calendar/connections").cookie(cookie(a)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.connections.length()").value(1));
        // Member B never sees member A's connection (FR-018).
        mvc.perform(get("/api/internal/calendar/connections").cookie(cookie(b)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.connections.length()").value(0));
    }

    @Test
    void unsupportedProvider_is400_onStartAndDelete() throws Exception {
        Cookie cookie = cookie(member("rec@x.com", Role.RECRUITER));
        mvc.perform(post("/api/internal/calendar/connections/foobar/start").cookie(cookie).with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("unsupported_provider"));
        mvc.perform(delete("/api/internal/calendar/connections/foobar").cookie(cookie).with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("unsupported_provider"));
    }

    @Test
    void nonCallbackCalendarPath_stillReturns401_whenUnauthenticated() throws Exception {
        // Confirms the callback-specific redirect entry point did not break the /api/** 401 contract.
        mvc.perform(post("/api/internal/calendar/connections/google/start").with(csrf()))
            .andExpect(status().isUnauthorized());
    }
}
