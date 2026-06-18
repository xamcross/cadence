package com.cadence.api;

import com.cadence.domain.WorkspaceConfig;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Map;

/**
 * Request/response DTOs for the F03 workspace-configuration API (contracts/workspace-api.md).
 *
 * Request/response asymmetry (FR-017): the email-provider credential appears ONLY on the inbound
 * {@link EmailConfigRequest} (and {@code EmailConfigRequest} is a class with a {@code toString()}
 * that omits it, so a validation-error log of the bound DTO cannot leak it). NO response type carries
 * the credential — reads expose only {@code credentialSet}.
 */
public final class WorkspaceDtos {

    private WorkspaceDtos() {}

    public record WorkingHoursDto(LocalTime start, LocalTime end) {}

    /** First-run wizard body. {@code retentionAcknowledged} must be true (FR-004). */
    public record SetupRequest(
        String name,
        String timeZone,
        WorkingHoursDto workingHours,
        Integer slaSilenceWindowDays,
        Integer retentionPeriodDays,
        boolean retentionAcknowledged) {}

    /** Partial settings update — any null field is left unchanged (targeted $set). */
    public record SettingsPatch(
        String name,
        String timeZone,
        WorkingHoursDto workingHours,
        Integer slaSilenceWindowDays,
        Integer retentionPeriodDays,
        // F23 No-Show Defense cascade settings (Durations, ISO-8601 e.g. "PT24H"). Null = unchanged.
        Duration confirmationLeadTime,
        Duration unconfirmedEscalationDeadline,
        // F32 Interviewer Feedback settings (Durations, ISO-8601). Null = unchanged.
        Duration feedbackSubmissionDeadline,
        Duration feedbackReminderInterval) {}

    public record BrandingRequest(String brandColor) {}

    public record TemplateLockRequest(boolean locked) {}

    /**
     * Email config body. NOT a record: a record's generated toString() would print {@code credential}
     * in a MethodArgumentNotValidException/BindingResult on a validation failure (research D2/SEC-NIT-1).
     */
    public static final class EmailConfigRequest {
        private String sendingDomain;
        private String credential;

        public EmailConfigRequest() {}

        public String getSendingDomain() { return sendingDomain; }
        public void setSendingDomain(String sendingDomain) { this.sendingDomain = sendingDomain; }

        public String getCredential() { return credential; }
        public void setCredential(String credential) { this.credential = credential; }

        @Override
        public String toString() { return "EmailConfigRequest{sendingDomain=" + sendingDomain + ", credential=***}"; }
    }

    /** Full settings payload (Admin). Carries {@code credentialSet}, NEVER the credential value. */
    public record WorkspaceConfigResponse(
        boolean configured,
        String name,
        String timeZone,
        WorkingHoursDto workingHours,
        Integer slaSilenceWindowDays,
        Integer retentionPeriodDays,
        Instant retentionAcknowledgedAt,
        String brandColor,
        boolean hasLogo,
        String emailSendingDomain,
        boolean credentialSet,
        Map<String, Boolean> templateLocks,
        // F23 No-Show Defense cascade settings (null = workspace uses the global default).
        Duration confirmationLeadTime,
        Duration unconfirmedEscalationDeadline,
        // F32 Interviewer Feedback settings (null = workspace uses the global default).
        Duration feedbackSubmissionDeadline,
        Duration feedbackReminderInterval) {

        public static WorkspaceConfigResponse from(WorkspaceConfig c) {
            WorkingHoursDto wh = c.getWorkingHours() == null ? null
                : new WorkingHoursDto(c.getWorkingHours().getStart(), c.getWorkingHours().getEnd());
            return new WorkspaceConfigResponse(
                c.isConfigured(), c.getName(), c.getTimeZone(), wh,
                c.getSlaSilenceWindowDays() == 0 ? null : c.getSlaSilenceWindowDays(),
                c.getRetentionPeriodDays() == 0 ? null : c.getRetentionPeriodDays(),
                c.getRetentionAcknowledgedAt(), c.getBrandColor(), c.isHasLogo(),
                c.getEmailSendingDomain(), c.isCredentialSet(), c.getTemplateLocks(),
                c.getConfirmationLeadTime(), c.getUnconfirmedEscalationDeadline(),
                c.getFeedbackSubmissionDeadline(), c.getFeedbackReminderInterval());
        }

        /** The unconfigured default response (no document exists yet). */
        public static WorkspaceConfigResponse unconfigured() {
            return new WorkspaceConfigResponse(false, null, null, null, null, null, null,
                null, false, null, false, Map.of(), null, null, null, null);
        }
    }

    /** Public candidate-facing branding — non-PII brand assets only (FR-011/FR-013). */
    public record BrandingResponse(String brandColor, String logoUrl) {}
}
