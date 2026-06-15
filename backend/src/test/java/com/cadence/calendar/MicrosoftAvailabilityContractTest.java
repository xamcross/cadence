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

/** US1 contract: preview is self-scoped + 200 for every role on a MICROSOFT connection; no-store; no content. */
class MicrosoftAvailabilityContractTest extends CalendarApiItBase {

    private static final String URL = "/api/internal/calendar/availability/preview";

    @Test
    void preview_is200ForEveryAuthenticatedRole_microsoft() throws Exception {
        for (Role role : Role.values()) {
            Member m = member("role-" + role.name() + "@x.com", role);
            connect(m, CalendarProvider.MICROSOFT, "role-" + role.name() + "@contoso.com");
            mvc.perform(get(URL).cookie(cookie(m)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.provider").value("MICROSOFT"));
        }
    }

    @Test
    void preview_unauthenticated_is401() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void preview_carriesIntervalsButNoEventContent() throws Exception {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.MICROSOFT, "alex@contoso.com");
        mscal.addItem(Instant.parse("2026-06-16T13:00:00Z"), Instant.parse("2026-06-16T14:00:00Z"),
            "busy", "SENTINEL_SUBJECT", "SENTINEL_LOCATION");

        mvc.perform(get(URL).cookie(cookie(m)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.provider").value("MICROSOFT"))
            .andExpect(jsonPath("$.status").value("DATA"))
            .andExpect(jsonPath("$.busy[0].start").exists())
            .andExpect(content().string(not(containsString("subject"))))
            .andExpect(content().string(not(containsString("location"))))
            .andExpect(content().string(not(containsString("SENTINEL_SUBJECT"))));
    }

    @Test
    void preview_isSelfScoped_twoMembersNeverCross() throws Exception {
        Member connected = member("conn@x.com", Role.RECRUITER);
        connect(connected, CalendarProvider.MICROSOFT, "conn@contoso.com");
        Member bare = member("bare@x.com", Role.RECRUITER); // no connection

        mvc.perform(get(URL).cookie(cookie(connected)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DATA"));
        mvc.perform(get(URL).cookie(cookie(bare)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("NOT_CONNECTED"));
    }
}
