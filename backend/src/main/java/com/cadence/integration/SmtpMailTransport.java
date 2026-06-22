package com.cadence.integration;

import com.cadence.config.EmailDeliveryProperties;
import com.cadence.config.MailConfig.MailSenderSelector;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * The production {@link MailTransport} (F22, research D1) — sends over SMTP via the per-workspace
 * {@link JavaMailSender} selected by {@link MailSenderSelector} (workspace F03 credential, else the
 * app-level default). A per-message {@link MimeMessage} is built; the {@code Message-ID} header is set to
 * {@code OutboundEmail.messageId} (the idempotency-key hash — a best-effort provider-side dedup hint, D5).
 *
 * <p>Outcome classification: no sender available -> {@code NO_PROVIDER_CONFIG} (permanent, FR-004); a
 * {@link MailParseException} (malformed message) is permanent; {@link MailAuthenticationException} and any
 * other {@link MailSendException} / {@link MessagingException} are classified <b>transient</b> (a relay
 * blip retries). Never logs the recipient/subject/body — only ids + a value-free reason code (D10).
 */
@Component
public class SmtpMailTransport implements MailTransport {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailTransport.class);

    private final MailSenderSelector selector;
    private final EmailDeliveryProperties props;

    public SmtpMailTransport(MailSenderSelector selector, EmailDeliveryProperties props) {
        this.selector = selector;
        this.props = props;
    }

    @Override
    public SendOutcome transmit(OutboundEmail message) {
        MailSenderSelector.Selection selection = selector.forWorkspace(message.workspaceId());
        if (!selection.present()) {
            log.warn("Email transport: no provider configured",
                StructuredArguments.kv("workspaceId", message.workspaceId()));
            return SendOutcome.permanentFailure("NO_PROVIDER_CONFIG");
        }
        JavaMailSender sender = selection.sender();
        try {
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, StandardCharsets.UTF_8.name());
            // From MUST be a provider-verified sender (e.g. Brevo) or the relay rejects the message.
            // Only set when configured so tests / no-From environments are unaffected.
            String from = props.getFrom();
            if (from != null && !from.isBlank()) {
                try {
                    helper.setFrom(from, props.getFromName());
                } catch (UnsupportedEncodingException e) {
                    helper.setFrom(from);
                }
            }
            helper.setTo(message.toAddress());
            helper.setSubject(message.subject());
            helper.setText(message.htmlBody(), true);
            if (message.messageId() != null && !message.messageId().isBlank()) {
                // Best-effort provider-side dedup hint (D5): set the SMTP Message-ID to the idempotency hash.
                mime.setHeader("Message-ID", "<" + message.messageId() + "@cadence>");
            }
            sender.send(mime);
            return SendOutcome.accepted(message.messageId());
        } catch (MailParseException e) {
            // Malformed message — retrying cannot help.
            log.warn("Email transport: permanent send failure",
                StructuredArguments.kv("workspaceId", message.workspaceId()),
                StructuredArguments.kv("reasonCode", "PARSE"));
            return SendOutcome.permanentFailure("PARSE");
        } catch (MailAuthenticationException e) {
            log.warn("Email transport: transient auth failure",
                StructuredArguments.kv("workspaceId", message.workspaceId()),
                StructuredArguments.kv("reasonCode", "AUTH"));
            return SendOutcome.transientFailure("AUTH");
        } catch (MailSendException | MessagingException e) {
            log.warn("Email transport: transient send failure",
                StructuredArguments.kv("workspaceId", message.workspaceId()),
                StructuredArguments.kv("reasonCode", "SEND"));
            return SendOutcome.transientFailure("SEND");
        }
    }
}
