package com.cadence.service;

import com.cadence.domain.EmailMessageType;
import com.cadence.domain.InterviewPresetKey;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Code-shipped interview-template presets. Static, workspace-free structural values only - member ids
 * are always chosen by the recruiter at apply time, and applying goes through the normal create path
 * (full validation; no backdoor). Mirrors the BuiltInEmailTemplates/TonePresetCatalogue fail-fast
 * pattern so preset updates ship with releases and a bad constant cannot boot.
 */
@Component
public class InterviewTemplatePresetCatalogue {

    /** poolN null = no pool suggested; optionalShadow = suggest one optional (shadow) seat. */
    public record Preset(InterviewPresetKey key, int durationMinutes, int slotCadenceMinutes,
                         int bufferBeforeMinutes, int bufferAfterMinutes, int dailyCapPerInterviewer,
                         int requiredCount, boolean optionalShadow, Integer poolN,
                         List<EmailMessageType> starterEmailTypes) {}

    private static final List<Preset> PRESETS = List.of(
        new Preset(InterviewPresetKey.PHONE_SCREEN, 30, 15, 0, 5, 4, 1, false, null,
            List.of(EmailMessageType.INVITATION, EmailMessageType.CONFIRMATION)),
        new Preset(InterviewPresetKey.HM_INTRO, 45, 15, 5, 5, 3, 1, false, null,
            List.of(EmailMessageType.INVITATION)),
        new Preset(InterviewPresetKey.TECH_DEEP_DIVE, 60, 30, 10, 10, 2, 1, true, null,
            List.of(EmailMessageType.INVITATION, EmailMessageType.CONFIRMATION, EmailMessageType.REMINDER_24H)),
        new Preset(InterviewPresetKey.PANEL_LOOP, 90, 30, 15, 15, 1, 1, false, 2,
            List.of(EmailMessageType.INVITATION, EmailMessageType.CONFIRMATION, EmailMessageType.REMINDER_24H)),
        new Preset(InterviewPresetKey.HR_CULTURE, 45, 15, 5, 5, 3, 1, false, null,
            List.of(EmailMessageType.INVITATION)),
        new Preset(InterviewPresetKey.FINAL_ROUND, 60, 30, 10, 10, 2, 1, false, 1,
            List.of(EmailMessageType.INVITATION, EmailMessageType.CONFIRMATION)));

    @PostConstruct
    void verifyComplete() {
        for (Preset p : PRESETS) {
            if (p.slotCadenceMinutes() < 1 || p.slotCadenceMinutes() > p.durationMinutes()
                || p.bufferBeforeMinutes() < 0 || p.bufferAfterMinutes() < 0
                || p.dailyCapPerInterviewer() < 1 || p.requiredCount() < 1
                || (p.poolN() != null && p.poolN() < 1)
                || p.starterEmailTypes().isEmpty()) {
                throw new IllegalStateException("Invalid interview preset " + p.key().name());
            }
        }
    }

    public List<Preset> all() {
        return PRESETS;
    }
}
