package com.cadence.scheduler;

import com.cadence.domain.DeadLetterRecord;
import com.cadence.integration.EmailSender;
import com.cadence.repository.DeadLetterRepository;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.regex.Pattern;

@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    private final DeadLetterRepository repository;
    private final EmailSender emailSender;

    public DeadLetterService(DeadLetterRepository repository, EmailSender emailSender) {
        this.repository = repository;
        this.emailSender = emailSender;
    }

    public void recordFailure(String taskName, Throwable ex, String candidateId) {
        String rawMessage = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        String sanitisedSummary = EMAIL_PATTERN.matcher(rawMessage).replaceAll("[REDACTED]");

        DeadLetterRecord record = new DeadLetterRecord(
            taskName,
            Instant.now(),
            ex.getClass().getName(),
            sanitisedSummary
        );
        record.setAffectedCandidateId(candidateId);

        DeadLetterRecord saved = repository.save(record);

        try {
            emailSender.sendSystemAlert(taskName, sanitisedSummary);
            saved.setAlertSentAt(Instant.now());
            repository.save(saved);
        } catch (Exception alertEx) {
            log.error("Failed to send dead-letter system alert",
                StructuredArguments.kv("taskName", taskName),
                StructuredArguments.kv("alertError", alertEx.getClass().getSimpleName()));
        }
    }
}
