package com.cadence.service;

import com.cadence.domain.EmailMessageType;
import com.cadence.domain.InterviewPresetKey;
import com.cadence.service.BuiltInEmailTemplates.Content;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Starter email wording per (interview preset, message type) - applied as per-stage F21 variants when
 * a template is created from a preset. Each starter derives from the built-in default of the same type
 * by inserting a token-free, preset-specific paragraph before the privacy footer, so merge-token safety
 * and the GDPR footer are inherited from BuiltInEmailTemplates by construction (the TonePresetCatalogue
 * "flavour the base" pattern). Content is never logged.
 */
@Component
public class PresetEmailStarterCatalogue {

    private static final String PRIVACY_MARKER = "\n\nView our Privacy Notice:";

    private final Map<String, Content> starters = new HashMap<>();

    public PresetEmailStarterCatalogue(BuiltInEmailTemplates builtins) {
        put(builtins, InterviewPresetKey.PHONE_SCREEN, EmailMessageType.INVITATION,
            "This is a short introductory phone screen - no preparation needed beyond a quiet spot and a good connection.");
        put(builtins, InterviewPresetKey.PHONE_SCREEN, EmailMessageType.CONFIRMATION,
            "This is a short introductory call - we will keep it focused and on time.");

        put(builtins, InterviewPresetKey.HM_INTRO, EmailMessageType.INVITATION,
            "This conversation with the hiring manager focuses on the role, the team, and your experience - no technical preparation needed.");

        put(builtins, InterviewPresetKey.TECH_DEEP_DIVE, EmailMessageType.INVITATION,
            "This technical session includes hands-on problem solving. Please be ready to share your screen and have your preferred development environment set up.");
        put(builtins, InterviewPresetKey.TECH_DEEP_DIVE, EmailMessageType.CONFIRMATION,
            "Please have your development environment ready - the session includes hands-on coding with screen sharing.");
        put(builtins, InterviewPresetKey.TECH_DEEP_DIVE, EmailMessageType.REMINDER_24H,
            "A quick reminder to have your development environment set up and screen sharing tested before the session.");

        put(builtins, InterviewPresetKey.PANEL_LOOP, EmailMessageType.INVITATION,
            "You will meet several interviewers in one longer session covering different topic areas. Short breaks are included.");
        put(builtins, InterviewPresetKey.PANEL_LOOP, EmailMessageType.CONFIRMATION,
            "Your panel session brings together several interviewers - the agenda is covered at the start.");
        put(builtins, InterviewPresetKey.PANEL_LOOP, EmailMessageType.REMINDER_24H,
            "A reminder about your panel session - you will meet several interviewers, with short breaks included.");

        put(builtins, InterviewPresetKey.HR_CULTURE, EmailMessageType.INVITATION,
            "This conversation focuses on ways of working, values, and what you are looking for - no preparation needed.");

        put(builtins, InterviewPresetKey.FINAL_ROUND, EmailMessageType.INVITATION,
            "This is the final conversation in the process - a chance to close remaining questions on both sides.");
        put(builtins, InterviewPresetKey.FINAL_ROUND, EmailMessageType.CONFIRMATION,
            "You are confirmed for the final round - we will cover any remaining questions on both sides.");
    }

    private void put(BuiltInEmailTemplates builtins, InterviewPresetKey preset, EmailMessageType type,
                     String paragraph) {
        starters.put(key(preset, type), withParagraph(builtins.forType(type), paragraph));
    }

    private static String key(InterviewPresetKey preset, EmailMessageType type) {
        return preset.name() + "|" + type.name();
    }

    /** Insert the paragraph before the privacy footer (or append if the built-in has no footer). */
    private static Content withParagraph(Content base, String paragraph) {
        int i = base.body().lastIndexOf(PRIVACY_MARKER);
        String body = i >= 0
            ? base.body().substring(0, i) + "\n\n" + paragraph + base.body().substring(i)
            : base.body() + "\n\n" + paragraph;
        return new Content(base.subject(), body);
    }

    @PostConstruct
    void verifyComplete() {
        for (Map.Entry<String, Content> e : starters.entrySet()) {
            Content c = e.getValue();
            if (c == null || c.subject() == null || c.subject().isBlank()
                || c.body() == null || c.body().isBlank()) {
                throw new IllegalStateException("Missing or empty preset starter for " + e.getKey());
            }
        }
    }

    /** null when the preset declares no starter for this type (the service maps that to a 400). */
    public Content forPresetAndType(InterviewPresetKey preset, EmailMessageType type) {
        return starters.get(key(preset, type));
    }
}
