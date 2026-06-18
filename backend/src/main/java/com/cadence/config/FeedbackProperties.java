package com.cadence.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * F32 Interviewer Feedback global knobs ({@code cadence.feedback.*}). The per-workspace deadline + reminder
 * cadence live on {@code WorkspaceConfig} (nullable Durations, the F23 pattern); these are the global defaults
 * (applied when a workspace leaves them unset), plus the post-interview generation offset, the token TTL, the
 * scan batch cap, and the generation query window floor.
 *
 * <p>{@code generationDelay} (research D2): the booked interview END instant is NOT denormalized (only
 * {@code bookedStartAt}), so the "interview occurred" trigger is {@code bookedStartAt + generationDelay} — a
 * conservative offset covering a typical interview length so a feedback request is only created after the
 * interview has plausibly ended.
 */
@Component
@ConfigurationProperties(prefix = "cadence.feedback")
public class FeedbackProperties {

    /** Post-interview offset before a feedback request is generated (covers interview length; research D2). */
    private Duration generationDelay = Duration.ofHours(3);

    /** Global default deadline (first reminder at generation + this) when a workspace leaves it unset. */
    private Duration submissionDeadline = Duration.ofHours(24);

    /** Global default escalation interval between reminders when a workspace leaves it unset. */
    private Duration reminderInterval = Duration.ofHours(24);

    /** Bounded maximum number of escalating reminders (global; FR-012 documented maximum). */
    private int maxReminders = 3;

    /** Scorecard link time-to-live from generation. */
    private Duration tokenTtl = Duration.ofHours(72);

    /** Generation/reminder scan fixed delay. */
    private Duration scanInterval = Duration.ofMinutes(5);

    /** Per-page cap on each scan stage (index-backed bounded read). */
    private int scanBatchLimit = 500;

    /**
     * Window floor for the generation scan: only BOOKED interviews whose start is within this window are
     * considered for feedback generation. ACCEPTED BOUND (review #1): an ungenerated occurrence older than this
     * (e.g. after a scheduler outage longer than the window) is not back-generated. Default 30 days is far beyond
     * the normal 5-minute sweep; operators can widen it if a long outage is anticipated.
     */
    private Duration generationQueryLowerBound = Duration.ofHours(720);

    /** SPA base path of the candidate-class scorecard page (the F30 {@code spaStatusBasePath} precedent). */
    private String spaFeedbackBasePath = "/feedback";

    @PostConstruct
    void validate() {
        requirePositive(generationDelay, "generationDelay");
        requirePositive(submissionDeadline, "submissionDeadline");
        requirePositive(reminderInterval, "reminderInterval");
        requirePositive(tokenTtl, "tokenTtl");
        requirePositive(scanInterval, "scanInterval");
        requirePositive(generationQueryLowerBound, "generationQueryLowerBound");
        if (maxReminders < 1 || maxReminders > 10) {
            throw new IllegalStateException("cadence.feedback.maxReminders must be in 1..10");
        }
        if (scanBatchLimit < 1) {
            throw new IllegalStateException("cadence.feedback.scanBatchLimit must be positive");
        }
    }

    private static void requirePositive(Duration d, String name) {
        if (d == null || d.isZero() || d.isNegative()) {
            throw new IllegalStateException("cadence.feedback." + name + " must be a positive duration");
        }
    }

    public Duration getGenerationDelay() { return generationDelay; }
    public void setGenerationDelay(Duration generationDelay) { this.generationDelay = generationDelay; }

    public Duration getSubmissionDeadline() { return submissionDeadline; }
    public void setSubmissionDeadline(Duration submissionDeadline) { this.submissionDeadline = submissionDeadline; }

    public Duration getReminderInterval() { return reminderInterval; }
    public void setReminderInterval(Duration reminderInterval) { this.reminderInterval = reminderInterval; }

    public int getMaxReminders() { return maxReminders; }
    public void setMaxReminders(int maxReminders) { this.maxReminders = maxReminders; }

    public Duration getTokenTtl() { return tokenTtl; }
    public void setTokenTtl(Duration tokenTtl) { this.tokenTtl = tokenTtl; }

    public Duration getScanInterval() { return scanInterval; }
    public void setScanInterval(Duration scanInterval) { this.scanInterval = scanInterval; }

    public int getScanBatchLimit() { return scanBatchLimit; }
    public void setScanBatchLimit(int scanBatchLimit) { this.scanBatchLimit = scanBatchLimit; }

    public Duration getGenerationQueryLowerBound() { return generationQueryLowerBound; }
    public void setGenerationQueryLowerBound(Duration generationQueryLowerBound) { this.generationQueryLowerBound = generationQueryLowerBound; }

    public String getSpaFeedbackBasePath() { return spaFeedbackBasePath; }
    public void setSpaFeedbackBasePath(String spaFeedbackBasePath) { this.spaFeedbackBasePath = spaFeedbackBasePath; }
}
