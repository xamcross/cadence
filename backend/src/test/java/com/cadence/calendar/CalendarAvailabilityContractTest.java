package com.cadence.calendar;

import com.cadence.domain.CalendarProvider;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** US1 contract: preview is self-scoped, 200 for every role, 401 unauth, no-store, never any event content. */
class CalendarAvailabilityContractTest extends CalendarApiItBase {

    private static final String URL = "/api/internal/calendar/availability/preview";

    @Test
    void preview_is200ForEveryAuthenticatedRole() throws Exception {
        for (Role role : Role.values()) {
            Member m = member("role-" + role.name() + "@x.com", role);
            mvc.perform(get(URL).cookie(cookie(m)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        }
    }

    @Test
    void preview_unauthenticated_is401() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void preview_carriesIntervalsButNoEventContent() throws Exception {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "alex@gmail.com");
        gcal.addBusy(Instant.parse("2026-06-16T13:00:00Z"), Instant.parse("2026-06-16T14:00:00Z"),
            "SENTINEL_TITLE", "sentinel@example.invalid");

        mvc.perform(get(URL).cookie(cookie(m)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DATA"))
            .andExpect(jsonPath("$.busy[0].start").exists())
            .andExpect(content().string(not(containsString("summary"))))
            .andExpect(content().string(not(containsString("title"))))
            .andExpect(content().string(not(containsString("attendee"))))
            .andExpect(content().string(not(containsString("SENTINEL_TITLE"))));
    }

    @Test
    void preview_isSelfScoped_reflectsTheCaller() throws Exception {
        Member connected = member("conn@x.com", Role.RECRUITER);
        connect(connected, CalendarProvider.GOOGLE, "conn@gmail.com");
        Member bare = member("bare@x.com", Role.RECRUITER); // no connection

        mvc.perform(get(URL).cookie(cookie(connected)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DATA"));
        mvc.perform(get(URL).cookie(cookie(bare)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("NOT_CONNECTED"));
    }
}
