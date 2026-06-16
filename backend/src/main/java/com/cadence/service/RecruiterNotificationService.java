package com.cadence.service;

import com.cadence.domain.RecruiterNotification;
import com.cadence.domain.RecruiterNotificationType;
import com.cadence.repository.RecruiterNotificationRepository;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * In-app recruiter-notification seam (F22, T044). A dispatch outcome a recruiter must see — a consent
 * REFUSED (FR-008), a terminal FAILED (FR-012), or a hard BOUNCED (FR-017) — is recorded as a persisted,
 * <b>value-free</b> {@link RecruiterNotification} (workspaceId + candidateId + type only, never the
 * recipient address / rendered subject / body / provider free-text). The full pipeline surface (badges,
 * read-state) is F51; this is the durable record + the wiring point those features read.
 *
 * <p>PII discipline (D10): logs carry ids + {@code .name()} Strings only — never the enum to {@code kv},
 * never any candidate-resolvable value.
 */
@Service
public class RecruiterNotificationService {

    private static final Logger log = LoggerFactory.getLogger(RecruiterNotificationService.class);

    private final RecruiterNotificationRepository repo;
    private final Clock clock;

    public RecruiterNotificationService(RecruiterNotificationRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** Record a value-free recruiter notification (ids + type only). Best-effort: a write failure never aborts dispatch. */
    public void notify(String workspaceId, String candidateId, RecruiterNotificationType type) {
        try {
            RecruiterNotification n = new RecruiterNotification();
            n.setWorkspaceId(workspaceId);
            n.setCandidateId(candidateId);
            n.setType(type);
            n.setCreatedAt(Instant.now(clock));
            repo.save(n);
            log.info("recruiter notification recorded {} {}",
                StructuredArguments.kv("candidateId", candidateId),
                StructuredArguments.kv("type", type.name())); // .name() only — never the enum (kv footgun)
        } catch (RuntimeException e) {
            // The notification is a recruiter-visibility aid, not the durable signal (audit/dead-letter are).
            log.warn("recruiter notification record failed {} {}",
                StructuredArguments.kv("candidateId", candidateId),
                StructuredArguments.kv("errorType", e.getClass().getSimpleName()));
        }
    }
}
