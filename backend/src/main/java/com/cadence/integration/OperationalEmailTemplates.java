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

    /**
     * templateId "feedback-request" (F32 — sent to an interviewer, an internal member, via the non-consent-gated
     * member-mail path). Single placeholder {@code {link}} (the candidate-class scorecard URL), substituted in
     * {@code SmtpEmailSender}. No candidate PII.
     */
    public static final String FEEDBACK_REQUEST_ID = "feedback-request";
    public static final String FEEDBACK_REQUEST_SUBJECT = "Please share your interview feedback";
    public static final String FEEDBACK_REQUEST_BODY =
        "You recently interviewed a candidate.<br><br>"
        + "Please take a couple of minutes to complete your scorecard:<br>"
        + "<a href=\"{link}\">{link}</a><br><br>"
        + "No login is required.";

    /**
     * templateId "feedback-reminder" (F32 — escalating reminder to an interviewer). Placeholders {@code {link}}
     * and {@code {urgency}} (the reminder level marker). Every {@code {key}} MUST be supplied at the call site
     * (the operational {@code substitute} leaves an unknown key literal — no F21 missing-field warning on this
     * path; FR-011).
     */
    public static final String FEEDBACK_REMINDER_ID = "feedback-reminder";
    public static final String FEEDBACK_REMINDER_SUBJECT = "Reminder ({urgency}): your interview feedback is needed";
    public static final String FEEDBACK_REMINDER_BODY =
        "This is reminder {urgency}: your interview scorecard is still outstanding.<br><br>"
        + "Please complete it here:<br>"
        + "<a href=\"{link}\">{link}</a><br><br>"
        + "No login is required.";

    /** The dead-letter / scheduler system alert (F00.2 sendSystemAlert). */
    public static final String SYSTEM_ALERT_SUBJECT = "Cadence scheduler task failed";
    public static final String SYSTEM_ALERT_BODY =
        "A Cadence background task reported a failure.<br><br>"
        + "Task: {task}<br><br>"
        + "Check the dispatch backlog metric and dead-letter records for details.";
}
