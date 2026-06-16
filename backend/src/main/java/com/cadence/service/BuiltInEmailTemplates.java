package com.cadence.service;

import com.cadence.domain.EmailMessageType;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Code-shipped built-in default subject+body for every {@link EmailMessageType} (F21 D1). A workspace
 * that never edits a type renders these by reference, so a future release's improved default reaches
 * un-edited workspaces with no migration. Every default uses only tokens the {@link MergeTokenCatalogue}
 * permits for that type. A {@code @PostConstruct} completeness check fails fast if any type is missing
 * or empty (SC-001). EN only (single-language MVP). No PII, no secret.
 */
@Component
public class BuiltInEmailTemplates {

    public record Content(String subject, String body) {}

    private final Map<EmailMessageType, Content> defaults = new EnumMap<>(EmailMessageType.class);

    public BuiltInEmailTemplates() {
        defaults.put(EmailMessageType.INVITATION, new Content(
            "Schedule your interview with {{workspace_name}}",
            "Hi {{candidate_name}},\n\nWe'd like to schedule your {{stage_name}} interview. "
                + "Please pick a time that works for you ({{time_zone}}):\n\n{{scheduling_link}}\n\n"
                + "We aim to confirm by {{expected_date}}.\n\nBest,\n{{recruiter_name}}, {{workspace_name}}"));
        defaults.put(EmailMessageType.CONFIRMATION, new Content(
            "Your {{stage_name}} interview is confirmed",
            "Hi {{candidate_name}},\n\nYour interview is confirmed for {{interview_date}} at "
                + "{{interview_time}} ({{time_zone}}).\nLocation: {{location}}\n\n"
                + "Need to change it? {{reschedule_link}}\n\nBest,\n{{recruiter_name}}"));
        defaults.put(EmailMessageType.REMINDER_24H, new Content(
            "Reminder: your interview tomorrow",
            "Hi {{candidate_name}},\n\nThis is a reminder that your {{stage_name}} interview is on "
                + "{{interview_date}} at {{interview_time}} ({{time_zone}}).\nLocation: {{location}}\n\n"
                + "Reschedule if needed: {{reschedule_link}}\n\nBest,\n{{recruiter_name}}"));
        defaults.put(EmailMessageType.REMINDER_1H, new Content(
            "Your interview starts soon",
            "Hi {{candidate_name}},\n\nYour {{stage_name}} interview starts at {{interview_time}} "
                + "({{time_zone}}) today.\nLocation: {{location}}\n\nSee you soon,\n{{recruiter_name}}"));
        defaults.put(EmailMessageType.HOLD_UPDATE, new Content(
            "An update on your application",
            "Hi {{candidate_name}},\n\nThanks for your patience. You can see your current status and "
                + "next steps here:\n\n{{status_link}}\n\nWe expect an update by {{expected_date}}.\n\n"
                + "Best,\n{{recruiter_name}}, {{workspace_name}}"));
        defaults.put(EmailMessageType.REJECTION, new Content(
            "An update on your application with {{workspace_name}}",
            "Hi {{candidate_name}},\n\nThank you for taking the time to interview with us. After careful "
                + "consideration we won't be moving forward at this time.\n\nYou can view your status here: "
                + "{{status_link}}\n\nWe wish you the very best,\n{{recruiter_name}}, {{workspace_name}}"));
        defaults.put(EmailMessageType.FEEDBACK_REQUEST, new Content(
            "Please share your interview feedback",
            "Hi {{candidate_name}},\n\nPlease take a few minutes to complete your {{stage_name}} "
                + "scorecard:\n\n{{feedback_link}}\n\nThank you,\n{{recruiter_name}}"));
        defaults.put(EmailMessageType.SLA_HOLDING, new Content(
            "We're still working on your application",
            "Hi {{candidate_name}},\n\nWe wanted to let you know we're still reviewing your application "
                + "and haven't forgotten you. See your status here:\n\n{{status_link}}\n\n"
                + "We expect an update by {{expected_date}}.\n\nBest,\n{{recruiter_name}}, {{workspace_name}}"));
        // F20: candidate cancellation notice (recruiter-initiated cancel). Universal tokens only — resolved
        // from the candidate/workspace records, so no per-send context is required.
        defaults.put(EmailMessageType.CANCELLATION, new Content(
            "Your interview with {{workspace_name}} has been cancelled",
            "Hi {{candidate_name}},\n\nYour interview with {{workspace_name}} has been cancelled. "
                + "We will be in touch about next steps.\n\nBest,\n{{recruiter_name}}, {{workspace_name}}"));
    }

    @PostConstruct
    void verifyComplete() {
        for (EmailMessageType type : EmailMessageType.values()) {
            Content c = defaults.get(type);
            if (c == null || c.subject() == null || c.subject().isBlank()
                || c.body() == null || c.body().isBlank()) {
                throw new IllegalStateException("Missing or empty built-in email template for " + type.name());
            }
        }
    }

    public Content forType(EmailMessageType type) {
        return defaults.get(type);
    }
}
