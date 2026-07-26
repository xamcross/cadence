package com.cadence.emailtemplate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec-mandated (2026-07-26 presets design, section 5): across the two new preset paths — the read-only
 * gallery and {@code apply-preset-starter} — NO sentinel, the interview-stage template NAME, appears in
 * any {@code com.cadence} log even at TRACE; only ids/keys/type Strings are logged. Neither new endpoint
 * itself logs (the gallery is a static catalogue read; apply-preset-starter's audit records ids/type/stage
 * only, mirroring apply-tone), so a known-logging create call in the same driven window supplies the
 * non-vacuity guard, exactly like {@code InterviewTemplateLogPiiScanTest}'s "interview template created".
 */
class PresetStarterLogPiiScanTest extends EmailTemplateItBase {

    private static final String SENT_NAME = "SENTINELPRESETNAME_zz9";

    @Test
    void noStageTemplateName_appearsInLogs_acrossPresetGalleryAndApplyPresetStarter() throws Exception {
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));
        Member interviewer = member("interviewer@x.com", Role.INTERVIEWER);
        InterviewTemplate stage = seedStage(WS, "stage1");
        stage.setName(SENT_NAME);
        mongoTemplate.save(stage);

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Logger cadence = (Logger) LoggerFactory.getLogger("com.cadence");
        Level previous = cadence.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        cadence.setLevel(Level.TRACE);
        try {
            mvc.perform(get("/api/internal/interview-templates/presets").cookie(rec).with(csrf()))
                .andExpect(status().isOk());

            mvc.perform(post("/api/internal/email-templates/INVITATION/apply-preset-starter")
                    .cookie(rec).with(csrf()).contentType("application/json")
                    .content("{\"stageKey\":\"stage1\",\"presetKey\":\"TECH_DEEP_DIVE\"}"))
                .andExpect(status().isOk());

            // Neither preset endpoint above logs. Drive a known-logging action (interview-template create)
            // in the same captured window as the non-vacuity guard, reusing the sentinel as the template name.
            mvc.perform(post("/api/internal/interview-templates").cookie(rec).with(csrf())
                    .contentType("application/json")
                    .content("{\"name\":\"" + SENT_NAME + "\",\"durationMinutes\":45,\"slotCadenceMinutes\":15,"
                        + "\"bufferBeforeMinutes\":0,\"bufferAfterMinutes\":0,\"dailyCapPerInterviewer\":2,"
                        + "\"requiredMemberIds\":[\"" + interviewer.getId() + "\"]}"))
                .andExpect(status().isOk());

            boolean ranThePath = false;
            for (ILoggingEvent e : appender.list) {
                assertThat(text(e)).doesNotContain(SENT_NAME);
                if (e.getFormattedMessage() != null && e.getFormattedMessage().contains("interview template created")) {
                    ranThePath = true;
                }
            }
            assertThat(ranThePath).as("the vacuity-guard create actually logged").isTrue();
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
