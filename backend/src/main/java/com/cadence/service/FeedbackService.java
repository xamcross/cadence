package com.cadence.service;

import com.cadence.api.FeedbackDtos.InterviewFeedbackItem;
import com.cadence.api.FeedbackDtos.InterviewFeedbackView;
import com.cadence.api.FeedbackDtos.PendingItem;
import com.cadence.api.FeedbackDtos.Rating;
import com.cadence.api.FeedbackDtos.ScorecardFormView;
import com.cadence.api.FeedbackDtos.ScorecardSubmission;
import com.cadence.api.FeedbackDtos.ScorecardView;
import com.cadence.api.FeedbackDtos.SubmitResponse;
import com.cadence.api.RbacExceptions;
import com.cadence.api.SchedulingExceptions;
import com.cadence.config.AuthProperties;
import com.cadence.config.FeedbackProperties;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.CandidateEventType;
import com.cadence.domain.ClaimStatus;
import com.cadence.domain.FeedbackRequest;
import com.cadence.domain.FeedbackRequestStatus;
import com.cadence.domain.InterviewSlotClaim;
import com.cadence.domain.Member;
import com.cadence.domain.Recommendation;
import com.cadence.domain.RecruiterNotificationType;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.repository.FeedbackRequestRepository;
import com.cadence.repository.InterviewSlotClaimRepository;
import com.cadence.repository.SchedulingRequestRepository;
import com.cadence.repository.WorkspaceConfigRepository;
import com.cadence.scheduler.DeadLetterService;
import com.cadence.security.SecureTokens;
import com.cadence.security.TokenHasher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.result.UpdateResult;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * F32 Interviewer Feedback service — generate a scorecard request per interviewer after an interview occurs,
 * serve/accept the no-login scorecard, escalate reminders, let recruiters read scorecards, and wipe on candidate
 * erasure. Implements {@link FeedbackInvalidator} (the erasure seam). Injects NO status/erasure service, so the
 * F31 constructor cycle does not arise (research D8).
 *
 * <p>PII discipline (D14): the only PII at rest is the encrypted {@code scorecardPayload} + the reversibly-encrypted
 * {@code token}; logs carry ids + {@code .name()} only (never an enum to {@code kv}); the decrypted scorecard /
 * token never reach a logger / audit / dead-letter.
 */
@Service
public class FeedbackService implements FeedbackInvalidator {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    /** The fixed MVP scorecard rating dimensions (FR-008). */
    static final List<String> RATING_DIMENSIONS = List.of("Technical skills", "Communication", "Culture and values");
    private static final int MAX_COMMENT = 5000;

    /** Public state-envelope values (contract A/B) — no status-code oracle. */
    private static final String STATE_FORM = "FORM";
    private static final String STATE_USED = "USED";
    private static final String STATE_EXPIRED = "EXPIRED";
    private static final String STATE_SUBMITTED = "SUBMITTED";

    private final FeedbackRequestRepository feedback;
    private final SchedulingRequestRepository scheduling;
    private final InterviewSlotClaimRepository claims;
    private final WorkspaceConfigRepository configs;
    private final MongoTemplate mongo;
    private final com.cadence.integration.EmailSender emailSender;
    private final MemberService members;
    private final RecruiterNotificationService notifications;
    private final CandidateAuditService audit;
    private final CandidateRateLimiter rateLimiter;
    private final TokenHasher hasher;
    private final FeedbackProperties props;
    private final AuthProperties authProps;
    private final DeadLetterService deadLetter;
    private final ObjectMapper objectMapper;
    private final AtsWriteBackService atsWriteBacks;
    private final Clock clock;

    public FeedbackService(FeedbackRequestRepository feedback, SchedulingRequestRepository scheduling,
                           InterviewSlotClaimRepository claims, WorkspaceConfigRepository configs,
                           MongoTemplate mongo, com.cadence.integration.EmailSender emailSender,
                           MemberService members, RecruiterNotificationService notifications,
                           CandidateAuditService audit, CandidateRateLimiter rateLimiter, TokenHasher hasher,
                           FeedbackProperties props, AuthProperties authProps, DeadLetterService deadLetter,
                           ObjectMapper objectMapper, AtsWriteBackService atsWriteBacks, Clock clock) {
        this.feedback = feedback;
        this.scheduling = scheduling;
        this.claims = claims;
        this.configs = configs;
        this.mongo = mongo;
        this.emailSender = emailSender;
        this.members = members;
        this.notifications = notifications;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.hasher = hasher;
        this.props = props;
        this.authProps = authProps;
        this.deadLetter = deadLetter;
        this.objectMapper = objectMapper;
        this.atsWriteBacks = atsWriteBacks;
        this.clock = clock;
    }

    // ===================================== generation (US1) =============================================

    /**
     * Generate one feedback request per ACTIVE participant for an occurred interview (FR-001/FR-003/FR-006).
     * A CAS {@code {_id, status:BOOKED, feedbackGeneratedAt:null} -> set feedbackGeneratedAt} fires generation
     * exactly once across overlapping/replayed sweeps; the unique {@code {interviewEventId, interviewerMemberId}}
     * index makes the per-participant insert idempotent too. Each interviewer is member mail (NOT consent-gated).
     */
    public void generateForOccurredInterview(SchedulingRequest req, Instant now) {
        SchedulingRequest won = mongo.findAndModify(
            Query.query(Criteria.where("_id").is(req.getId())
                .and("status").is(com.cadence.domain.SchedulingStatus.BOOKED)
                .and("feedbackGeneratedAt").is(null)),
            new Update().set("feedbackGeneratedAt", now),
            FindAndModifyOptions.options().returnNew(true), SchedulingRequest.class);
        if (won == null) {
            return; // already generated, or no longer BOOKED — idempotent no-op
        }
        String ws = won.getWorkspaceId();
        String candidateId = won.getCandidateId();
        Instant expiry = now.plus(props.getTokenTtl());
        Instant firstReminderDue = now.plus(effectiveSubmissionDeadline(ws));
        int generated = 0;
        for (InterviewSlotClaim claim : claims.findByWorkspaceIdAndSchedulingRequestId(ws, won.getId())) {
            if (claim.getStatus() != ClaimStatus.ACTIVE) {
                continue;
            }
            String interviewerId = claim.getMemberId();
            Member m = members.findByIdOptional(interviewerId).orElse(null);
            if (m == null || !m.isActive()) {
                // FR-009: the interviewer is gone — cannot collect; alert the workspace fallback, no request.
                notifications.notify(ws, candidateId, RecruiterNotificationType.FEEDBACK_UNCOLLECTIBLE);
                continue;
            }
            String raw = SecureTokens.newToken();
            FeedbackRequest fr = new FeedbackRequest();
            fr.setWorkspaceId(ws);
            fr.setCandidateId(candidateId);
            fr.setInterviewEventId(won.getId());
            fr.setInterviewerMemberId(interviewerId);
            fr.setStatus(FeedbackRequestStatus.PENDING);
            fr.setTokenHash(hasher.hashToken(raw));
            fr.setToken(raw); // converter-encrypted at rest (re-derive the link for reminders)
            fr.setExpiresAt(expiry);
            fr.setReminderLevelSent(0);
            fr.setNextReminderDueAt(firstReminderDue);
            fr.setCreatedAt(now);
            fr.setUpdatedAt(now);
            try {
                feedback.insert(fr);
            } catch (DuplicateKeyException e) {
                continue; // already generated for this {occurrence, interviewer} — idempotent no-op
            }
            sendRequestEmail(interviewerId, raw, candidateId);
            generated++;
        }
        if (generated > 0) {
            log.info("feedback requests generated {} {}",
                StructuredArguments.kv("interviewEventId", won.getId()),
                StructuredArguments.kv("generated", generated));
        }
    }

    private void sendRequestEmail(String interviewerMemberId, String rawToken, String candidateId) {
        try {
            emailSender.sendEmail(interviewerMemberId,
                com.cadence.integration.OperationalEmailTemplates.FEEDBACK_REQUEST_ID,
                Map.of("link", linkFromRaw(rawToken)));
        } catch (RuntimeException e) {
            // PII-free summary only (the F22 dead-letter footgun) — never the raw exception.
            deadLetter.recordFailure("feedback-request", new IllegalStateException(
                "feedback_request_send_failed: " + e.getClass().getSimpleName()), candidateId);
        }
    }

    // ===================================== reminders (US2) ==============================================

    /**
     * Escalate one PENDING request if due (FR-012/FR-014/FR-015). Stop conditions: interviewer deactivated
     * (-> UNCOLLECTIBLE + fallback alert), TTL expired (-> EXPIRED), max reached (nextReminderDueAt -> null).
     * The send is guarded by a per-{request, level} CAS so an overlapping fire cannot double-send (SC-020).
     */
    public void sendReminderIfDue(FeedbackRequest req, Instant now) {
        Member m = members.findByIdOptional(req.getInterviewerMemberId()).orElse(null);
        if (m == null || !m.isActive()) {
            if (casFromPending(req.getId(), FeedbackRequestStatus.UNCOLLECTIBLE, now, true) != null) {
                notifications.notify(req.getWorkspaceId(), req.getCandidateId(),
                    RecruiterNotificationType.FEEDBACK_UNCOLLECTIBLE);
            }
            return;
        }
        if (req.getExpiresAt() != null && !now.isBefore(req.getExpiresAt())) {
            casFromPending(req.getId(), FeedbackRequestStatus.EXPIRED, now, true);
            return;
        }
        int level = req.getReminderLevelSent();
        boolean last = level + 1 >= props.getMaxReminders();
        Instant nextDue = last ? null : now.plus(effectiveReminderInterval(req.getWorkspaceId()));
        FeedbackRequest won = mongo.findAndModify(
            Query.query(Criteria.where("_id").is(req.getId())
                .and("status").is(FeedbackRequestStatus.PENDING)
                .and("reminderLevelSent").is(level)),
            new Update().set("reminderLevelSent", level + 1).set("lastReminderAt", now)
                .set("nextReminderDueAt", nextDue).set("updatedAt", now),
            FindAndModifyOptions.options().returnNew(true), FeedbackRequest.class);
        if (won == null) {
            return; // lost the per-level race / no longer PENDING — no second send
        }
        sendReminderEmail(won, level + 1);
    }

    private void sendReminderEmail(FeedbackRequest req, int urgency) {
        try {
            emailSender.sendEmail(req.getInterviewerMemberId(),
                com.cadence.integration.OperationalEmailTemplates.FEEDBACK_REMINDER_ID,
                Map.of("link", linkFromRaw(req.getToken()), "urgency", String.valueOf(urgency)));
        } catch (RuntimeException e) {
            deadLetter.recordFailure("feedback-reminder", new IllegalStateException(
                "feedback_reminder_send_failed: " + e.getClass().getSimpleName()), req.getCandidateId());
        }
    }

    /** CAS PENDING -> terminal; clears nextReminderDueAt. Returns the new row or null if not PENDING. */
    private FeedbackRequest casFromPending(String id, FeedbackRequestStatus to, Instant now, boolean clearDue) {
        Update u = new Update().set("status", to).set("updatedAt", now);
        if (clearDue) {
            u.set("nextReminderDueAt", null);
        }
        return mongo.findAndModify(
            Query.query(Criteria.where("_id").is(id).and("status").is(FeedbackRequestStatus.PENDING)),
            u, FindAndModifyOptions.options().returnNew(true), FeedbackRequest.class);
    }

    // ===================================== token: load / submit (US1) ===================================

    /** Load the BLANK scorecard form (write-only — no prior content). STATUS-before-TIME (FR-017/FR-030). */
    public ScorecardFormView loadForm(String rawToken, String ip) {
        rateLimit(ip);
        FeedbackRequest r = resolve(rawToken);
        String state = stateOf(r);
        if (!STATE_FORM.equals(state)) {
            return new ScorecardFormView(state, null, null, null); // USED / EXPIRED — no content
        }
        return new ScorecardFormView(STATE_FORM, interviewLabel(r),
            List.of(Recommendation.STRONG_YES.name(), Recommendation.YES.name(),
                Recommendation.NO.name(), Recommendation.STRONG_NO.name()),
            RATING_DIMENSIONS);
    }

    /** Submit the scorecard (single-effective via CAS). Validation 400; STATUS-before-TIME envelope otherwise. */
    public SubmitResponse submit(String rawToken, ScorecardSubmission body, String ip) {
        rateLimit(ip);
        FeedbackRequest r = resolve(rawToken);
        String state = stateOf(r);
        if (!STATE_FORM.equals(state)) {
            return new SubmitResponse(state); // USED / EXPIRED — nothing persisted, no content echoed
        }
        String payload = serialize(validate(body));
        Instant now = Instant.now(clock);
        FeedbackRequest won = mongo.findAndModify(
            Query.query(Criteria.where("_id").is(r.getId()).and("status").is(FeedbackRequestStatus.PENDING)),
            new Update().set("status", FeedbackRequestStatus.SUBMITTED).set("scorecardPayload", payload)
                .set("submittedAt", now).set("nextReminderDueAt", null).set("updatedAt", now),
            FindAndModifyOptions.options().returnNew(true), FeedbackRequest.class);
        if (won == null) {
            return new SubmitResponse(STATE_USED); // lost the race / already submitted — idempotent, no dup
        }
        audit.append(won.getWorkspaceId(), won.getCandidateId(), CandidateEventType.SCORECARD_SUBMITTED,
            CandidateAuditOutcome.RECORDED, won.getInterviewerMemberId());
        // F40: write the feedback-submitted signal to the ATS timeline (ids only, NEVER the scorecard payload).
        atsWriteBacks.enqueue(won.getWorkspaceId(), won.getCandidateId(),
            com.cadence.domain.AtsWriteBackType.FEEDBACK_SUBMITTED, now);
        log.info("scorecard submitted {} {}",
            StructuredArguments.kv("interviewEventId", won.getInterviewEventId()),
            StructuredArguments.kv("interviewerMemberId", won.getInterviewerMemberId()));
        return new SubmitResponse(STATE_SUBMITTED);
    }

    private FeedbackRequest resolve(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        return feedback.findByTokenHash(hasher.hashToken(rawToken)).orElse(null);
    }

    /** STATUS-before-TIME: null/non-PENDING -> USED (byte-identical); past-TTL PENDING -> EXPIRED; else FORM. */
    private String stateOf(FeedbackRequest r) {
        if (r == null || r.getStatus() != FeedbackRequestStatus.PENDING) {
            return STATE_USED;
        }
        if (r.getExpiresAt() != null && !Instant.now(clock).isBefore(r.getExpiresAt())) {
            return STATE_EXPIRED;
        }
        return STATE_FORM;
    }

    private String interviewLabel(FeedbackRequest r) {
        SchedulingRequest booking = scheduling.findByWorkspaceIdAndId(r.getWorkspaceId(), r.getInterviewEventId())
            .orElse(null);
        if (booking == null || booking.getBookedStartAt() == null) {
            return "Interview feedback";
        }
        LocalDate date = LocalDate.ofInstant(booking.getBookedStartAt(), workspaceZone(r.getWorkspaceId()));
        return "Interview on " + date;
    }

    // ===================================== recruiter read (US3) =========================================

    /** Per-interview feedback (FR-024). Resolve the booking first (no empty-list oracle, FR-026/SC-011). */
    public InterviewFeedbackView interviewFeedback(String workspaceId, String schedulingRequestId) {
        scheduling.findByWorkspaceIdAndId(workspaceId, schedulingRequestId)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        List<InterviewFeedbackItem> items = new ArrayList<>();
        for (FeedbackRequest r : feedback.findByWorkspaceIdAndInterviewEventId(workspaceId, schedulingRequestId)) {
            ScorecardView card = r.getStatus() == FeedbackRequestStatus.SUBMITTED
                ? deserialize(r.getScorecardPayload()) : null;
            items.add(new InterviewFeedbackItem(r.getInterviewerMemberId(), r.getStatus(), card, r.getSubmittedAt()));
        }
        return new InterviewFeedbackView(schedulingRequestId, items);
    }

    /** Workspace pending list (FR-027) — ids only, no PII. */
    public List<PendingItem> pendingList(String workspaceId) {
        List<PendingItem> out = new ArrayList<>();
        for (FeedbackRequest r : feedback.findByWorkspaceIdAndStatus(
                workspaceId, FeedbackRequestStatus.PENDING, PageRequest.of(0, props.getScanBatchLimit()))) {
            out.add(new PendingItem(r.getInterviewEventId(), r.getInterviewerMemberId(), r.getCandidateId(),
                r.getReminderLevelSent()));
        }
        return out;
    }

    // ===================================== erasure hook (FR-023) ========================================

    /**
     * Wipe the candidate's scorecard content as part of the erasure wipe — EVERY row, not just pending (the
     * review BLOCKER): a SUBMITTED row's encrypted payload is candidate-assessment PII and must be erased
     * (encryption with a retained workspace key is not Art. 17 erasure). {@code $set null} for the converter
     * fields (NEVER {@code $unset}); {@code $unset tokenHash} drops the link from the partial index (404).
     */
    @Override
    public void invalidateForCandidate(String workspaceId, String candidateId) {
        try {
            Instant now = Instant.now(clock);
            UpdateResult pending = mongo.updateMulti(
                Query.query(Criteria.where("workspaceId").is(workspaceId).and("candidateId").is(candidateId)
                    .and("status").is(FeedbackRequestStatus.PENDING)),
                new Update().set("status", FeedbackRequestStatus.INVALIDATED)
                    .set("scorecardPayload", null).set("token", null).unset("tokenHash")
                    .set("nextReminderDueAt", null).set("updatedAt", now),
                FeedbackRequest.class);
            UpdateResult submitted = mongo.updateMulti(
                Query.query(Criteria.where("workspaceId").is(workspaceId).and("candidateId").is(candidateId)
                    .and("status").is(FeedbackRequestStatus.SUBMITTED)),
                new Update().set("scorecardPayload", null).set("token", null).unset("tokenHash")
                    .set("updatedAt", now),
                FeedbackRequest.class);
            if (pending.getModifiedCount() > 0 || submitted.getModifiedCount() > 0) {
                audit.append(workspaceId, candidateId, CandidateEventType.FEEDBACK_INVALIDATED,
                    CandidateAuditOutcome.RECORDED, null);
            }
        } catch (RuntimeException e) {
            log.warn("feedback invalidate on erasure failed {} {}",
                StructuredArguments.kv("workspaceId", workspaceId),
                StructuredArguments.kv("errorType", e.getClass().getSimpleName()));
        }
    }

    // ===================================== helpers =====================================================

    private Duration effectiveSubmissionDeadline(String workspaceId) {
        Duration ws = configs.findByWorkspaceId(workspaceId)
            .map(WorkspaceConfig::getFeedbackSubmissionDeadline).orElse(null);
        return ws != null ? ws : props.getSubmissionDeadline();
    }

    private Duration effectiveReminderInterval(String workspaceId) {
        Duration ws = configs.findByWorkspaceId(workspaceId)
            .map(WorkspaceConfig::getFeedbackReminderInterval).orElse(null);
        return ws != null ? ws : props.getReminderInterval();
    }

    private ScorecardSubmission validate(ScorecardSubmission body) {
        if (body == null || body.recommendation() == null || body.recommendation().isBlank()) {
            throw new IllegalArgumentException("A recommendation is required.");
        }
        try {
            Recommendation.valueOf(body.recommendation());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid recommendation.");
        }
        if (body.ratings() != null) {
            for (Rating r : body.ratings()) {
                if (r == null || r.score() < 1 || r.score() > 4) {
                    throw new IllegalArgumentException("Each rating must be 1..4.");
                }
            }
        }
        if (body.comment() != null && body.comment().length() > MAX_COMMENT) {
            throw new IllegalArgumentException("Comment too long.");
        }
        return body;
    }

    private String serialize(ScorecardSubmission body) {
        try {
            return objectMapper.writeValueAsString(
                new ScorecardView(body.recommendation(), body.ratings(), body.comment()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("scorecard_serialize_failed: " + e.getClass().getSimpleName());
        }
    }

    private ScorecardView deserialize(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ScorecardView.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("scorecard_deserialize_failed: " + e.getClass().getSimpleName());
        }
    }

    private String linkFromRaw(String raw) {
        return authProps.getSpaBaseUrl() + props.getSpaFeedbackBasePath() + "?token=" + raw;
    }

    private ZoneId workspaceZone(String workspaceId) {
        return configs.findByWorkspaceId(workspaceId)
            .map(WorkspaceConfig::getTimeZone)
            .filter(tz -> tz != null && !tz.isBlank())
            .map(ZoneId::of)
            .orElse(ZoneId.of("UTC"));
    }

    private void rateLimit(String ip) {
        if (!rateLimiter.tryAcquire(ip)) {
            throw new SchedulingExceptions.RateLimitedException();
        }
    }
}
