package com.cadence.integration;

import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The no-op email sender, retained as a non-primary bean for the {@code noop-email} profile (e.g. a
 * local run with no SMTP relay configured). {@code @Primary} was REMOVED in F22: {@link SmtpEmailSender}
 * is now the sole {@code @Primary EmailSender} (two primaries would fail startup with
 * {@code NoUniqueBeanDefinitionException}). Scoped to a profile so it does not collide with the primary
 * in the default/test contexts.
 */
@Component
@Profile("noop-email")
public class NoOpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(NoOpEmailSender.class);

    @Override
    public void sendEmail(String toInternalId, String templateId, Map<String, String> mergeFields) {
        log.debug("NoOpEmailSender: sendEmail skipped",
            StructuredArguments.kv("templateId", templateId));
    }

    @Override
    public void sendSystemAlert(String taskName, String errorSummary) {
        log.error("Scheduler task failed — system alert (no-op)",
            StructuredArguments.kv("taskName", taskName));
    }

    @Override
    public SendOutcome send(OutboundEmail message) {
        log.debug("NoOpEmailSender: send skipped",
            StructuredArguments.kv("workspaceId", message.workspaceId()));
        return SendOutcome.accepted("noop-" + message.messageId());
    }
}
