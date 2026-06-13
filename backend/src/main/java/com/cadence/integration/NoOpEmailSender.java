package com.cadence.integration;

import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Primary
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
}
