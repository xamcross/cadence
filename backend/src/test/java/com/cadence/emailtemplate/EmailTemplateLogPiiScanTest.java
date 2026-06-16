package com.cadence.emailtemplate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.cadence.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SC-009: across edit + preview (success) AND a failing preview/edit (error paths), NO sentinel — the
 * template body content or the candidate name — appears in any {@code com.cadence} log even at TRACE;
 * only ids/type/stage Strings are logged. A positive vacuity guard proves the path actually logged.
 */
class EmailTemplateLogPiiScanTest extends EmailTemplateItBase {

    private static final String SENT_BODY = "SENTINELF21BODY_zz9";
    private static final String SENT_CAND = "SENTINELF21CANDIDATE_zz9";

    @Test
    void noTemplateContentOrCandidateName_appearsInLogs_includingErrorPaths() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        seedCandidate(WS, "cand1", SENT_CAND);

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Logger cadence = (Logger) LoggerFactory.getLogger("com.cadence");
        Level previous = cadence.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        cadence.setLevel(Level.TRACE);
        try {
            // success path: edit with the body sentinel, then preview merging the candidate name
            mvc.perform(put("/api/internal/email-templates/INVITATION").cookie(admin).with(csrf())
                    .contentType("application/json")
                    .content("{\"stageKey\":\"BASE\",\"subject\":\"S {{workspace_name}}\",\"body\":\"Hi {{candidate_name}} " + SENT_BODY + "\"}"))
                .andExpect(status().isOk());
            mvc.perform(post("/api/internal/email-templates/INVITATION/preview").cookie(admin).with(csrf())
                    .contentType("application/json").content("{\"stageKey\":\"BASE\",\"candidateId\":\"cand1\"}"))
                .andExpect(status().isOk());

            // error paths: a failing preview (foreign candidate -> 404) and a rejected edit carrying the body sentinel
            mvc.perform(post("/api/internal/email-templates/INVITATION/preview").cookie(admin).with(csrf())
                    .contentType("application/json").content("{\"stageKey\":\"BASE\",\"candidateId\":\"ghost\"}"))
                .andExpect(status().isNotFound());
            mvc.perform(put("/api/internal/email-templates/INVITATION").cookie(admin).with(csrf())
                    .contentType("application/json")
                    .content("{\"stageKey\":\"BASE\",\"subject\":\"\",\"body\":\"Hi {{not_a_token}} " + SENT_BODY + "\"}"))
                .andExpect(status().isBadRequest());

            boolean ranThePath = false;
            for (ILoggingEvent e : appender.list) {
                assertThat(text(e)).doesNotContain(SENT_BODY).doesNotContain(SENT_CAND);
                if (e.getFormattedMessage() != null && e.getFormattedMessage().contains("email template edited")) {
                    ranThePath = true;
                }
            }
            assertThat(ranThePath).as("the edit path actually logged (non-vacuous scan)").isTrue();
        } finally {
            cadence.setLevel(previous);
            root.detachAppender(appender);
        }
    }

    /** Formatted message plus any throwable message/stack — an exception must not carry the value either. */
    private static String text(ILoggingEvent e) {
        StringBuilder sb = new StringBuilder();
        if (e.getFormattedMessage() != null) sb.append(e.getFormattedMessage());
        IThrowableProxy tp = e.getThrowableProxy();
        while (tp != null) {
            if (tp.getMessage() != null) sb.append(' ').append(tp.getMessage());
            sb.append(' ').append(tp.getClassName());
            tp = tp.getCause();
        }
        return sb.toString();
    }
}
