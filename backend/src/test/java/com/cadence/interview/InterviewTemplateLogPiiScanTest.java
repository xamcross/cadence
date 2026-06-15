package com.cadence.interview;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SC-010: across create + compute, NO sentinel — the recruiter-supplied template NAME or a member email
 * — appears in any {@code com.cadence} log even at TRACE; only ids/status Strings are logged. A positive
 * vacuity guard proves the path actually logged.
 */
class InterviewTemplateLogPiiScanTest extends InterviewItBase {

    private static final String SENT_NAME = "SENTINELF12NAME_zz9";
    private static final String SENT_EMAIL = "sentinel-f12-member@example.invalid";

    @Test
    void noTemplateNameOrMemberEmail_appearsInLogs_atTrace() throws Exception {
        configuredWorkspace(WS, "UTC", LocalTime.of(9, 0), LocalTime.of(17, 0));
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));
        Member interviewer = member(SENT_EMAIL, Role.INTERVIEWER);

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Logger cadence = (Logger) LoggerFactory.getLogger("com.cadence");
        Level previous = cadence.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        cadence.setLevel(Level.TRACE);
        try {
            String body = "{\"name\":\"" + SENT_NAME + "\",\"durationMinutes\":45,\"slotCadenceMinutes\":15,"
                + "\"bufferBeforeMinutes\":0,\"bufferAfterMinutes\":0,\"dailyCapPerInterviewer\":2,"
                + "\"requiredMemberIds\":[\"" + interviewer.getId() + "\"]}";
            String json = mvc.perform(post("/api/internal/interview-templates").cookie(rec).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String id = mapper(json);

            mvc.perform(post("/api/internal/interview-templates/" + id + "/slots").cookie(rec).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"rangeStart\":\"2026-06-15\",\"rangeEnd\":\"2026-06-20\"}"))
                .andExpect(status().isOk());

            boolean ranThePath = false;
            for (ILoggingEvent e : appender.list) {
                String line = e.getFormattedMessage();
                if (line == null) {
                    continue;
                }
                assertThat(line).doesNotContain(SENT_NAME);
                assertThat(line).doesNotContain(SENT_EMAIL);
                if (line.contains("interview template created")) {
                    ranThePath = true;
                }
            }
            assertThat(ranThePath).as("the create path actually logged (non-vacuous scan)").isTrue();
        } finally {
            cadence.setLevel(previous);
            root.detachAppender(appender);
        }
    }

    private static String mapper(String json) {
        // tiny extraction to avoid a field; the response always has "id":"...".
        int i = json.indexOf("\"id\":\"") + 6;
        return json.substring(i, json.indexOf('"', i));
    }
}
