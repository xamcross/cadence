package com.cadence.feedback;

import com.cadence.integration.OperationalEmailTemplates;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-011 (pure unit): the operational {@code substitute} leaves an unsupplied {@code {key}} literal (no F21
 * missing-field warning on the member-mail path), so every placeholder in the two F32 templates MUST be in the
 * set of keys the call sites supply ({@code link} for the request; {@code link, urgency} for the reminder). A
 * literal {@code {key}} must never ship to an interviewer.
 */
class OperationalTemplateMergeTest {

    private static final Pattern KEY = Pattern.compile("\\{([a-z_]+)}");

    private static Set<String> placeholders(String... templates) {
        Set<String> out = new HashSet<>();
        for (String t : templates) {
            Matcher m = KEY.matcher(t);
            while (m.find()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    @Test
    void feedbackRequestTemplate_onlySuppliedKeys() {
        Set<String> supplied = Set.of("link");
        assertThat(placeholders(
            OperationalEmailTemplates.FEEDBACK_REQUEST_SUBJECT, OperationalEmailTemplates.FEEDBACK_REQUEST_BODY))
            .isSubsetOf(supplied);
    }

    @Test
    void feedbackReminderTemplate_onlySuppliedKeys() {
        Set<String> supplied = Set.of("link", "urgency");
        assertThat(placeholders(
            OperationalEmailTemplates.FEEDBACK_REMINDER_SUBJECT, OperationalEmailTemplates.FEEDBACK_REMINDER_BODY))
            .isSubsetOf(supplied);
    }
}
