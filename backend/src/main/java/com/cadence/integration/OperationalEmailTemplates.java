package com.cadence.integration;

/**
 * Small built-in operational email constants (F22, research D3) — distinct from F21's candidate-facing
 * {@code EmailMessageType} library. Plain, link-bearing, no candidate PII. The single {@code {link}}
 * placeholder is substituted by a literal {@link String#replace} in {@code SmtpEmailSender} (no merge
 * engine — these are not candidate sends). {@code SYSTEM_ALERT} carries a {@code {task}} placeholder
 * for the failing scheduler task name (value-free).
 */
public final class OperationalEmailTemplates {

    private OperationalEmailTemplates() {}

    /** templateId "invitation" (F01 InvitationService). */
    public static final String INVITATION_ID = "invitation";
    public static final String INVITATION_SUBJECT = "You have been invited to Cadence";
    public static final String INVITATION_BODY =
        "You have been invited to join a Cadence workspace.<br><br>"
        + "Use the link below to accept your invitation:<br>"
        + "<a href=\"{link}\">{link}</a><br><br>"
        + "If you were not expecting this invitation you can ignore this message.";

    /** templateId "password-reset" (F01 PasswordResetService). */
    public static final String PASSWORD_RESET_ID = "password-reset";
    public static final String PASSWORD_RESET_SUBJECT = "Reset your Cadence password";
    public static final String PASSWORD_RESET_BODY =
        "A password reset was requested for your Cadence account.<br><br>"
        + "Use the link below to choose a new password:<br>"
        + "<a href=\"{link}\">{link}</a><br><br>"
        + "If you did not request this you can safely ignore this message.";

    /**
     * templateId "interview-confirmation" (F13 participant confirmation — sent to internal panel members
     * via the non-consent-gated member-mail path). Placeholders {@code {title}/{date}/{time}/{timezone}/
     * {location}} are substituted from the merge map in {@code SmtpEmailSender}. No candidate PII — these
     * are the interview details the interviewer needs.
     */
    public static final String INTERVIEW_CONFIRMATION_ID = "interview-confirmation";
    public static final String INTERVIEW_CONFIRMATION_SUBJECT = "Interview scheduled: {title}";
    public static final String INTERVIEW_CONFIRMATION_BODY =
        "An interview has been scheduled and added to your calendar.<br><br>"
        + "Interview: {title}<br>"
        + "Date: {date}<br>"
        + "Time: {time} ({timezone})<br>"
        + "Location: {location}<br><br>"
        + "This event has been placed on your calendar automatically.";

    /** The dead-letter / scheduler system alert (F00.2 sendSystemAlert). */
    public static final String SYSTEM_ALERT_SUBJECT = "Cadence scheduler task failed";
    public static final String SYSTEM_ALERT_BODY =
        "A Cadence background task reported a failure.<br><br>"
        + "Task: {task}<br><br>"
        + "Check the dispatch backlog metric and dead-letter records for details.";
}
