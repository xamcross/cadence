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

    // A MongoDB ObjectId is exactly 24 hex characters. Anything else passed as a candidate
    // identifier (e.g. an email or name) is rejected to uphold the zero-PII guarantee (§VIII).
    private static final Pattern OBJECT_ID_PATTERN = Pattern.compile("^[0-9a-fA-F]{24}$");

    private final DeadLetterRepository repository;
    private final EmailSender emailSender;

    public DeadLetterService(DeadLetterRepository repository, EmailSender emailSender) {
        this.repository = repository;
        this.emailSender = emailSender;
    }

    public void recordFailure(String taskName, Throwable ex, String candidateId) {
        String rawMessage = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        String sanitisedSummary = sanitise(rawMessage);

        DeadLetterRecord record = new DeadLetterRecord(
            taskName,
            Instant.now(),
            // errorType is a class name, but a custom exception name could embed data — sanitise it too.
            sanitise(ex.getClass().getName()),
            sanitisedSummary
        );
        // Never persist a raw candidate identifier that is not a bare ObjectId — it could be PII.
        record.setAffectedCandidateId(sanitiseCandidateId(candidateId));

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

    private String sanitise(String value) {
        return value == null ? null : EMAIL_PATTERN.matcher(value).replaceAll("[REDACTED]");
    }

    private String sanitiseCandidateId(String candidateId) {
        if (candidateId == null) {
            return null;
        }
        return OBJECT_ID_PATTERN.matcher(candidateId).matches() ? candidateId : "[REDACTED]";
    }
}
