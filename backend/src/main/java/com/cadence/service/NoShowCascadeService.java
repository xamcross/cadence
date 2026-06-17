package com.cadence.service;

import com.cadence.api.SchedulingExceptions;
import com.cadence.config.AuthProperties;
import com.cadence.config.SchedulingProperties;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.CandidateEventType;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.RecruiterNotificationType;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.repository.InterviewTemplateRepository;
import com.cadence.repository.SchedulingRequestRepository;
import com.cadence.security.SecureTokens;
import com.cadence.security.TokenHasher;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * F23 No-Show Defense cascade logic (research D1/D3/D5/D8). Three per-booking stages — confirmation request,
 * unconfirmed escalation, no-show stamp — each a {@code findAndModify} CAS so a duplicate/overlapping sweep or
 * a missed-fire replay is a clean no-op; plus the candidate confirm action. Driven by
 * {@code NoShowDefenseScheduler} (the timing) and {@code CandidateBookingController} (the confirm endpoint).
 *
 * <p>PII discipline: logs carry ids + {@code .name()} only; the confirm credential is hashed at rest and never
 * logged; the recruiter alert is the single value-free coarse {@code INTERVIEW_UNCONFIRMED} (no contactability
 * oracle, D5). The candidate confirm payload exposes times only.
 */
@Service
public class NoShowCascadeService {

    private static final Logger log = LoggerFactory.getLogger(NoShowCascadeService.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEE, d MMM yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final SchedulingRequestRepository requests;
    private final InterviewTemplateRepository templates;
    private final ContactPermissionGate gate;
    private final EmailDispatchService dispatch;
    private final RecruiterNotificationService notifications;
    private final AuthAuditService audit;
    private final CandidateAuditService candidateAudit;
    private final CandidateRateLimiter rateLimiter;
    private final TokenHasher hasher;
    private final MongoTemplate mongo;
    private final SchedulingProperties props;
    private final AuthProperties authProps;
    private final Clock clock;

    public NoShowCascadeService(SchedulingRequestRepository requests, InterviewTemplateRepository templates,
                                ContactPermissionGate gate, EmailDispatchService dispatch,
                                RecruiterNotificationService notifications, AuthAuditService audit,
                                CandidateAuditService candidateAudit, CandidateRateLimiter rateLimiter,
                                TokenHasher hasher, MongoTemplate mongo, SchedulingProperties props,
                                AuthProperties authProps, Clock clock) {
        this.requests = requests;
        this.templates = templates;
        this.gate = gate;
        this.dispatch = dispatch;
        this.notifications = notifications;
        this.audit = audit;
        this.candidateAudit = candidateAudit;
        this.rateLimiter = rateLimiter;
        this.hasher = hasher;
        this.mongo = mongo;
        this.props = props;
        this.authProps = authProps;
        this.clock = clock;
    }

    public record ConfirmResult(String status, Instant bookedStart, String zoneId, Instant at) {}

    // ===================================== Cascade stage 1: request =====================================

    /**
     * Stage 1 (FR-001/FR-005): CAS-claim the confirmation-request stage; if contactable, mint a fresh confirm
     * token and enqueue the consent-gated {@code REMINDER_24H}; if not contactable, set the value-free
     * {@code confirmationNotRequestable} flag (no email) so stage 2 still escalates. CAS-claim BEFORE enqueue
     * (D8): a crash between loses at most one reminder, caught by the escalation — duplicates impossible.
     */
    public void requestConfirmation(SchedulingRequest req, Instant now) {
        // Evaluate contactability BEFORE claiming, then fold the stamp + (token | not-requestable flag) into ONE
        // atomic CAS. This (a) closes the erasure/cancel race — a CAS that no longer matches status:BOOKED never
        // writes a confirmTokenHash onto an erased/cancelled row (D9), and (b) mints the token atomically with the
        // claim, so a crash can never leave confirmationRequestedAt set with no token (the D8 honest bound only
        // covers a lost *email*, never a lost *credential*).
        boolean contactable = gate.evaluate(req.getWorkspaceId(), req.getCandidateId()).permit();
        String rawConfirm = contactable ? SecureTokens.newToken() : null;
        Update update = new Update().set("confirmationRequestedAt", now).set("updatedAt", now);
        if (contactable) {
            update.set("confirmTokenHash", hasher.hashToken(rawConfirm));
        } else {
            update.set("confirmationNotRequestable", true); // value-free; stage 2 still escalates (D5)
        }
        SchedulingRequest claimed = mongo.findAndModify(
            Query.query(Criteria.where("_id").is(req.getId()).and("status").is(SchedulingStatus.BOOKED)
                .and("confirmationRequestedAt").is(null)),
            update, FindAndModifyOptions.options().returnNew(true), SchedulingRequest.class);
        if (claimed == null) {
            return; // already requested / no longer BOOKED (e.g. erased between gate eval and CAS) — no-op
        }
        audit.record(AuthEventType.NOSHOW_CONFIRMATION_REQUESTED, claimed.getWorkspaceId(), "SYSTEM",
            "confirmation_requested", null);
        if (contactable) {
            enqueueReminder(claimed, rawConfirm, now);
        }
    }

    private void enqueueReminder(SchedulingRequest req, String rawConfirm, Instant now) {
        OfferedSlot slot = chosenSlot(req);
        ZoneId zone = ZoneId.of(slot != null ? slot.getZoneId() : "UTC");
        Map<String, String> ctx = new HashMap<>();
        ctx.put("confirm_link", confirmLink(rawConfirm));
        if (slot != null) {
            ctx.put("interview_date", DATE_FMT.format(slot.getStart().atZone(zone)));
            ctx.put("interview_time", TIME_FMT.format(slot.getStart().atZone(zone)));
            ctx.put("time_zone", slot.getZoneId());
        }
        ctx.put("location", req.getLocationText() == null ? "" : req.getLocationText());
        InterviewTemplate template = templates
            .findByWorkspaceIdAndId(req.getWorkspaceId(), req.getTemplateId()).orElse(null);
        ctx.put("stage_name", template != null ? template.getName() : "");
        try {
            dispatch.enqueue(req.getWorkspaceId(), req.getCandidateId(), EmailMessageType.REMINDER_24H,
                "BASE", now, ctx, null);
        } catch (RuntimeException e) {
            // Best-effort (D8): a lost reminder is caught by the stage-2 escalation; never roll back the claim.
            log.warn("no-show confirmation reminder enqueue failed (escalation will catch it) {} {}",
                StructuredArguments.kv("schedulingRequestId", req.getId()),
                StructuredArguments.kv("errorType", e.getClass().getSimpleName()));
        }
    }

    // ===================================== Cascade stage 2: escalate ====================================

    /** Stage 2 (FR-010): CAS-stamp the escalation and raise exactly one coarse {@code INTERVIEW_UNCONFIRMED}. */
    public void escalateUnconfirmed(SchedulingRequest req, Instant now) {
        SchedulingRequest escalated = mongo.findAndModify(
            Query.query(Criteria.where("_id").is(req.getId()).and("status").is(SchedulingStatus.BOOKED)
                .and("confirmationRequestedAt").ne(null).and("candidateConfirmedAt").is(null)
                .and("escalatedAt").is(null)),
            new Update().set("escalatedAt", now).set("updatedAt", now),
            FindAndModifyOptions.options().returnNew(true), SchedulingRequest.class);
        if (escalated == null) {
            return; // confirmed / already escalated / no longer BOOKED — idempotent no-op
        }
        notifications.notify(escalated.getWorkspaceId(), escalated.getCandidateId(),
            RecruiterNotificationType.INTERVIEW_UNCONFIRMED);
        audit.record(AuthEventType.NOSHOW_UNCONFIRMED_ESCALATED, escalated.getWorkspaceId(), "SYSTEM",
            "unconfirmed_escalated", null);
        log.info("no-show escalated {}", StructuredArguments.kv("schedulingRequestId", escalated.getId()));
    }

    // ===================================== Cascade stage 3: no-show =====================================

    /** Stage 3 (FR-016): CAS-stamp the no-show signal when the start is reached unconfirmed (data for F50). */
    public void stampNoShow(SchedulingRequest req, Instant now) {
        mongo.findAndModify(
            Query.query(Criteria.where("_id").is(req.getId()).and("status").is(SchedulingStatus.BOOKED)
                .and("candidateConfirmedAt").is(null).and("noShowAt").is(null)),
            new Update().set("noShowAt", now).set("updatedAt", now),
            SchedulingRequest.class);
    }

    // ===================================== Candidate confirm action ====================================

    /**
     * Candidate confirms attendance (FR-006/FR-007/FR-008/FR-009). Resolve SOLELY by the confirm credential
     * (no IDOR). Precedence is status-before-time (no oracle): unknown/cleared -> 400; not-BOOKED
     * (cancelled/released/rescheduled-away) -> 400; BOOKED but past -> 410; else CAS set
     * {@code candidateConfirmedAt} (idempotent replay returns the existing ack). The "past" check uses the
     * in-memory chosen-slot start, NOT {@code bookedStartAt}.
     */
    public ConfirmResult confirmAttendance(String rawToken, String ip) {
        rateLimit(ip);
        Instant now = Instant.now(clock);
        String hash = hasher.hashToken(rawToken);
        SchedulingRequest b = requests.findByConfirmTokenHash(hash)
            .orElseThrow(SchedulingExceptions.TokenInvalidException::new);
        if (b.getStatus() != SchedulingStatus.BOOKED) {
            throw new SchedulingExceptions.TokenInvalidException(); // cancelled/released/rescheduled-away
        }
        Instant start = chosenStart(b);
        if (start == null || !start.isAfter(now)) {
            throw new SchedulingExceptions.TokenExpiredException(); // interview in the past -> distinct 410
        }
        if (b.getCandidateConfirmedAt() != null) {
            return confirmed(b, start); // idempotent replay (no extra email, no extra recruiter signal)
        }
        SchedulingRequest done = mongo.findAndModify(
            Query.query(Criteria.where("confirmTokenHash").is(hash).and("status").is(SchedulingStatus.BOOKED)
                .and("candidateConfirmedAt").is(null)),
            new Update().set("candidateConfirmedAt", now).set("updatedAt", now),
            FindAndModifyOptions.options().returnNew(true), SchedulingRequest.class);
        if (done == null) {
            // Raced (a concurrent confirm won, or the row changed). Resolve idempotently.
            SchedulingRequest cur = requests.findByConfirmTokenHash(hash).orElse(null);
            if (cur != null && cur.getStatus() == SchedulingStatus.BOOKED && cur.getCandidateConfirmedAt() != null) {
                return confirmed(cur, chosenStart(cur));
            }
            throw new SchedulingExceptions.TokenInvalidException();
        }
        audit.record(AuthEventType.NOSHOW_ATTENDANCE_CONFIRMED, done.getWorkspaceId(), "CANDIDATE",
            "attendance_confirmed", null);
        candidateAudit.append(done.getWorkspaceId(), done.getCandidateId(),
            CandidateEventType.BOOKING_CHANGED, CandidateAuditOutcome.ATTENDANCE_CONFIRMED, "CANDIDATE");
        log.info("no-show attendance confirmed {}", StructuredArguments.kv("schedulingRequestId", done.getId()));
        return confirmed(done, start);
    }

    private ConfirmResult confirmed(SchedulingRequest b, Instant start) {
        OfferedSlot slot = chosenSlot(b);
        return new ConfirmResult("confirmed", start, slot != null ? slot.getZoneId() : "UTC",
            b.getCandidateConfirmedAt());
    }

    private void rateLimit(String ip) {
        if (!rateLimiter.tryAcquire(ip)) {
            throw new SchedulingExceptions.RateLimitedException();
        }
    }

    private String confirmLink(String rawToken) {
        return authProps.getSpaBaseUrl() + props.getSpaConfirmBasePath() + "?token=" + rawToken;
    }

    private static OfferedSlot chosenSlot(SchedulingRequest req) {
        if (req.getChosenSlotId() == null) {
            return null;
        }
        return req.getOfferedSlots().stream()
            .filter(s -> req.getChosenSlotId().equals(s.getSlotId())).findFirst().orElse(null);
    }

    private static Instant chosenStart(SchedulingRequest req) {
        OfferedSlot s = chosenSlot(req);
        return s != null ? s.getStart() : null;
    }
}
