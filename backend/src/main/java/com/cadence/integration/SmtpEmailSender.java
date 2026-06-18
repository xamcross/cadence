package com.cadence.integration;

import com.cadence.config.EmailDeliveryProperties;
import com.cadence.domain.Invitation;
import com.cadence.domain.Member;
import com.cadence.repository.InvitationRepository;
import com.cadence.service.MemberService;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The sole {@code @Primary EmailSender} (F22, research D3) — makes the F01 member-invitation /
 * password-reset and F00.2 dead-letter alert into real emails with no call-site edits.
 *
 * <ul>
 *   <li>{@link #sendEmail} resolves the recipient address — an {@code "invitation"} send resolves it from
 *       the {@code invitations} record (the member does not exist yet), a {@code "password-reset"} send
 *       from the {@link Member} via {@link MemberService} — then renders the matching
 *       {@link OperationalEmailTemplates} constant (literal {@code {link}} substitution) and transmits.</li>
 *   <li>{@link #sendSystemAlert} renders the alert constant and transmits to the configured ops address
 *       (the app-level default sender — workspace-independent, D12).</li>
 *   <li>{@link #send} transmits a pre-rendered candidate {@link OutboundEmail} (the F22 dispatch path).</li>
 * </ul>
 *
 * <p>Operational mail is NOT consent-gated (members aren't candidates). Logs only ids + {@code .name()}
 * Strings — never the recipient address, subject, or body, and never an enum to {@code kv} (D10).
 */
@Component
@Primary
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private static final String OPS_WORKSPACE = "__ops__";

    private final MailTransport transport;
    private final MemberService members;
    private final InvitationRepository invitations;
    private final EmailDeliveryProperties props;

    public SmtpEmailSender(MailTransport transport, MemberService members,
                           InvitationRepository invitations, EmailDeliveryProperties props) {
        this.transport = transport;
        this.members = members;
        this.invitations = invitations;
        this.props = props;
    }

    @Override
    public void sendEmail(String toInternalId, String templateId, Map<String, String> mergeFields) {
        String workspaceId;
        String toAddress;
        String subject;
        String bodyTemplate;

        if (OperationalEmailTemplates.INVITATION_ID.equals(templateId)) {
            Invitation inv = invitations.findById(toInternalId).orElse(null);
            if (inv == null) {
                log.warn("Operational email: invitation not found",
                    StructuredArguments.kv("templateId", templateId));
                return;
            }
            workspaceId = inv.getWorkspaceId();
            toAddress = inv.getEmail(); // converter-decrypted plaintext; never logged
            subject = OperationalEmailTemplates.INVITATION_SUBJECT;
            bodyTemplate = OperationalEmailTemplates.INVITATION_BODY;
        } else if (OperationalEmailTemplates.PASSWORD_RESET_ID.equals(templateId)) {
            Member m = members.findByIdOptional(toInternalId).orElse(null);
            if (m == null) {
                log.warn("Operational email: member not found",
                    StructuredArguments.kv("templateId", templateId));
                return;
            }
            workspaceId = m.getWorkspaceId();
            toAddress = m.getEmail(); // converter-decrypted plaintext; never logged
            subject = OperationalEmailTemplates.PASSWORD_RESET_SUBJECT;
            bodyTemplate = OperationalEmailTemplates.PASSWORD_RESET_BODY;
        } else if (OperationalEmailTemplates.INTERVIEW_CONFIRMATION_ID.equals(templateId)) {
            // F13: participant (internal panel member) interview confirmation. The member-mail path is a
            // CLOSED dispatcher; this branch resolves the member address and substitutes the interview
            // detail merge fields (title/date/time/timezone/location).
            Member m = members.findByIdOptional(toInternalId).orElse(null);
            if (m == null) {
                log.warn("Operational email: member not found",
                    StructuredArguments.kv("templateId", templateId));
                return;
            }
            workspaceId = m.getWorkspaceId();
            toAddress = m.getEmail(); // converter-decrypted plaintext; never logged
            subject = substitute(OperationalEmailTemplates.INTERVIEW_CONFIRMATION_SUBJECT, mergeFields);
            bodyTemplate = OperationalEmailTemplates.INTERVIEW_CONFIRMATION_BODY;
        } else if (OperationalEmailTemplates.FEEDBACK_REQUEST_ID.equals(templateId)) {
            // F32: interviewer scorecard request (sent to an internal panel member via the non-consent-gated
            // member-mail path). The per-recipient unique scorecard link rides mergeFields {link} + {stage}.
            Member m = members.findByIdOptional(toInternalId).orElse(null);
            if (m == null) {
                log.warn("Operational email: member not found",
                    StructuredArguments.kv("templateId", templateId));
                return;
            }
            workspaceId = m.getWorkspaceId();
            toAddress = m.getEmail(); // converter-decrypted plaintext; never logged
            subject = OperationalEmailTemplates.FEEDBACK_REQUEST_SUBJECT;
            bodyTemplate = OperationalEmailTemplates.FEEDBACK_REQUEST_BODY;
        } else if (OperationalEmailTemplates.FEEDBACK_REMINDER_ID.equals(templateId)) {
            // F32: escalating scorecard reminder. Subject + body carry {urgency} (the level marker).
            Member m = members.findByIdOptional(toInternalId).orElse(null);
            if (m == null) {
                log.warn("Operational email: member not found",
                    StructuredArguments.kv("templateId", templateId));
                return;
            }
            workspaceId = m.getWorkspaceId();
            toAddress = m.getEmail(); // converter-decrypted plaintext; never logged
            subject = substitute(OperationalEmailTemplates.FEEDBACK_REMINDER_SUBJECT, mergeFields);
            bodyTemplate = OperationalEmailTemplates.FEEDBACK_REMINDER_BODY;
        } else {
            log.warn("Operational email: unknown templateId",
                StructuredArguments.kv("templateId", templateId));
            return;
        }

        String body = substitute(bodyTemplate, mergeFields);
        SendOutcome outcome = transport.transmit(
            new OutboundEmail(workspaceId, toAddress, subject, body, null));
        log.debug("Operational email transmitted",
            StructuredArguments.kv("templateId", templateId),
            StructuredArguments.kv("accepted", outcome.accepted()));
    }

    /** Literal {@code {key}} substitution over the merge map (no merge engine — operational mail only). */
    private static String substitute(String template, Map<String, String> mergeFields) {
        if (mergeFields == null || template == null) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> e : mergeFields.entrySet()) {
            if (e.getValue() != null) {
                result = result.replace("{" + e.getKey() + "}", e.getValue());
            }
        }
        return result;
    }

    @Override
    public void sendSystemAlert(String taskName, String errorSummary) {
        String ops = props.getOpsAlertAddress();
        if (ops == null || ops.isBlank()) {
            log.error("Scheduler task failed — no ops address configured",
                StructuredArguments.kv("taskName", taskName));
            return;
        }
        String body = OperationalEmailTemplates.SYSTEM_ALERT_BODY.replace("{task}", taskName);
        // App-level default sender (workspace-independent ops mail). A full SMTP outage means this alert
        // email also fails (logged, never re-dead-lettered) — the actuator backlog metric is the
        // out-of-band signal (research D12).
        SendOutcome outcome = transport.transmit(
            new OutboundEmail(OPS_WORKSPACE, ops, OperationalEmailTemplates.SYSTEM_ALERT_SUBJECT, body, null));
        log.error("Scheduler task failed — system alert sent",
            StructuredArguments.kv("taskName", taskName),
            StructuredArguments.kv("accepted", outcome.accepted()));
    }

    @Override
    public SendOutcome send(OutboundEmail message) {
        return transport.transmit(message);
    }
}
