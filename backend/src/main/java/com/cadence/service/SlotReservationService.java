package com.cadence.service;

import com.cadence.api.RbacExceptions;
import com.cadence.api.SchedulingExceptions;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.BusyInterval;
import com.cadence.domain.EventDetails;
import com.cadence.domain.InterviewSlotClaim;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.MemberAvailability;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.PanelBookingResult;
import com.cadence.domain.Participant;
import com.cadence.domain.PoolRule;
import com.cadence.domain.SchedulingOutcomeReason;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.domain.RecruiterNotificationType;
import com.cadence.repository.InterviewSlotClaimRepository;
import com.cadence.repository.InterviewTemplateRepository;
import com.cadence.repository.SchedulingRequestRepository;
import com.cadence.security.TokenHasher;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F13 candidate self-scheduling (contract B): token-gated view of the offered slots (times only) and the
 * atomic confirm saga (research D3/D4/D9/D10). Confirm = request-status CAS -> contactability re-check ->
 * re-validate + pool re-select -> per-participant claim CAS -> panel calendar book -> confirmations -> audit.
 * The two-layer CAS (request status + per-participant unique-index claim) is the load-bearing no-double-book
 * guard. Value-free logs; the candidate payload exposes times only (FR-011).
 */
@Service
public class SlotReservationService {

    private static final Logger log = LoggerFactory.getLogger(SlotReservationService.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEE, d MMM yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final SchedulingRequestRepository requests;
    private final InterviewSlotClaimRepository claims;
    private final InterviewTemplateRepository templates;
    private final ContactPermissionGate gate;
    private final AvailabilityService availability;
    private final CalendarEventService calendar;
    private final EmailDispatchService dispatch;
    private final com.cadence.integration.EmailSender emailSender;
    private final AuthAuditService audit;
    private final RecruiterNotificationService notifications;
    private final CandidateRateLimiter rateLimiter;
    private final TokenHasher hasher;
    private final MongoTemplate mongo;
    private final Clock clock;

    public SlotReservationService(SchedulingRequestRepository requests, InterviewSlotClaimRepository claims,
                                  InterviewTemplateRepository templates, ContactPermissionGate gate,
                                  AvailabilityService availability, CalendarEventService calendar,
                                  EmailDispatchService dispatch, com.cadence.integration.EmailSender emailSender,
                                  AuthAuditService audit, RecruiterNotificationService notifications,
                                  CandidateRateLimiter rateLimiter, TokenHasher hasher, MongoTemplate mongo,
                                  Clock clock) {
        this.requests = requests;
        this.claims = claims;
        this.templates = templates;
        this.gate = gate;
        this.availability = availability;
        this.calendar = calendar;
        this.dispatch = dispatch;
        this.emailSender = emailSender;
        this.audit = audit;
        this.notifications = notifications;
        this.rateLimiter = rateLimiter;
        this.hasher = hasher;
        this.mongo = mongo;
        this.clock = clock;
    }

    public record SlotProjection(String slotId, Instant start, Instant end, String zoneId) {}

    public record ViewResult(boolean booked, Instant bookedStart, String zoneHint, List<SlotProjection> slots) {}

    public record ConfirmResult(Instant bookedStart, String zoneId) {}

    /** Token-gated view of offered slots — times only (FR-011), with the 410/400/200 precedence (D5). */
    public ViewResult view(String rawToken, String ip) {
        rateLimit(ip);
        SchedulingRequest req = requests.findByTokenHash(hasher.hashToken(rawToken))
            .orElseThrow(SchedulingExceptions.TokenInvalidException::new);
        Instant now = Instant.now(clock);
        return switch (req.getStatus()) {
            case BOOKED -> new ViewResult(true, chosenStart(req), zoneHint(req), List.of());
            case PENDING_SELECTION, BOOKING -> {
                if (req.getExpiresAt() != null && !now.isBefore(req.getExpiresAt())) {
                    throw new SchedulingExceptions.TokenExpiredException();
                }
                yield new ViewResult(false, null, zoneHint(req), project(req.getOfferedSlots()));
            }
            case EXPIRED -> throw new SchedulingExceptions.TokenExpiredException();
            // SUPERSEDED / CLEANUP_INCOMPLETE -> indistinguishable invalid (no oracle).
            default -> throw new SchedulingExceptions.TokenInvalidException();
        };
    }

    /** Atomic confirm saga (FR-012..FR-019). */
    public ConfirmResult confirm(String rawToken, String slotId, String ip) {
        rateLimit(ip);
        Instant now = Instant.now(clock);
        String hash = hasher.hashToken(rawToken);

        // 1) Request-status CAS: PENDING_SELECTION & not expired -> BOOKING. Only one confirm proceeds.
        SchedulingRequest claimed = mongo.findAndModify(
            Query.query(Criteria.where("tokenHash").is(hash)
                .and("status").is(SchedulingStatus.PENDING_SELECTION)
                .and("expiresAt").gt(now)),
            new Update().set("status", SchedulingStatus.BOOKING).set("chosenSlotId", slotId).set("updatedAt", now),
            FindAndModifyOptions.options().returnNew(true), SchedulingRequest.class);

        if (claimed == null) {
            // Lost the CAS / not pending / expired / unknown — resolve idempotently.
            SchedulingRequest cur = requests.findByTokenHash(hash).orElse(null);
            if (cur == null) throw new SchedulingExceptions.TokenInvalidException();
            if (cur.getStatus() == SchedulingStatus.BOOKED) {
                return new ConfirmResult(chosenStart(cur), zoneHint(cur)); // idempotent replay (FR-019)
            }
            if ((cur.getStatus() == SchedulingStatus.PENDING_SELECTION || cur.getStatus() == SchedulingStatus.BOOKING)
                && cur.getExpiresAt() != null && !now.isBefore(cur.getExpiresAt())) {
                throw new SchedulingExceptions.TokenExpiredException();
            }
            if (cur.getStatus() == SchedulingStatus.BOOKING) {
                throw new SchedulingExceptions.SlotTakenException(); // a concurrent confirm is in flight
            }
            if (cur.getStatus() == SchedulingStatus.EXPIRED) {
                throw new SchedulingExceptions.TokenExpiredException();
            }
            throw new SchedulingExceptions.TokenInvalidException(); // SUPERSEDED / CLEANUP_INCOMPLETE
        }

        try {
            return book(claimed, slotId, now);
        } catch (RuntimeException e) {
            // Any unexpected failure must not strand the request in BOOKING — release back to PENDING_SELECTION.
            if (!(e instanceof SchedulingExceptions.CleanupIncompleteException)) {
                revertToPending(claimed.getId(), now);
            }
            throw e;
        }
    }

    private ConfirmResult book(SchedulingRequest req, String slotId, Instant now) {
        OfferedSlot slot = req.getOfferedSlots().stream()
            .filter(s -> s.getSlotId().equals(slotId)).findFirst().orElse(null);
        if (slot == null) {
            revertToPending(req.getId(), now);
            throw new SchedulingExceptions.SlotNotFoundException();
        }

        // 2) Re-evaluate contactability at confirm (FR-014) — refuse (not suppress) for any deny reason.
        if (!gate.evaluate(req.getWorkspaceId(), req.getCandidateId()).permit()) {
            revertToPending(req.getId(), now);
            audit.record(AuthEventType.SCHEDULING_REFUSED, req.getWorkspaceId(), "CANDIDATE",
                SchedulingOutcomeReason.NOT_CONTACTABLE.name(), null);
            throw new SchedulingExceptions.NotAvailableException();
        }

        InterviewTemplate template = templates.findByWorkspaceIdAndId(req.getWorkspaceId(), req.getTemplateId())
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);

        // 3) Re-validate availability + bind the pool quorum (FR-013) over the buffered window.
        Instant bufStart = slot.getStart().minus(Duration.ofMinutes(template.getBufferBeforeMinutes()));
        Instant bufEnd = slot.getEnd().plus(Duration.ofMinutes(template.getBufferAfterMinutes()));
        Set<String> queryIds = new LinkedHashSet<>(slot.getRequiredMemberIds());
        for (PoolRule p : template.getPools()) queryIds.addAll(p.getMemberIds());
        Map<String, MemberAvailability> byMember = new HashMap<>();
        for (MemberAvailability ma : availability.query(req.getWorkspaceId(), bufStart, bufEnd, new ArrayList<>(queryIds))) {
            byMember.put(ma.memberId(), ma);
        }

        Set<String> participants = new LinkedHashSet<>();
        for (String m : slot.getRequiredMemberIds()) {
            if (!isFree(byMember.get(m), bufStart, bufEnd)) {
                staleRefuse(req.getId(), now);
            }
            participants.add(m);
        }
        for (PoolRule pool : template.getPools()) {
            int picked = 0;
            for (String m : pool.getMemberIds()) {
                if (picked >= pool.getN()) break;
                if (!participants.contains(m) && isFree(byMember.get(m), bufStart, bufEnd)) {
                    participants.add(m);
                    picked++;
                }
            }
            if (picked < pool.getN()) {
                staleRefuse(req.getId(), now);
            }
        }

        // 4) Per-participant claim CAS (the cross-request double-booking guard, D3).
        List<InterviewSlotClaim> inserted = new ArrayList<>();
        for (String memberId : participants) {
            try {
                inserted.add(claims.insert(new InterviewSlotClaim(
                    req.getWorkspaceId(), memberId, slot.getStart(), req.getId(), now)));
            } catch (DuplicateKeyException e) {
                releaseClaims(inserted, now);
                revertToPending(req.getId(), now);
                throw new SchedulingExceptions.SlotTakenException();
            }
        }

        // 5) Book the calendar events (provider-first, compensating-delete rollback, F10/F11).
        // Wrap from here so ANY unexpected throw after the claims were inserted (a Mongo error, a bad stored
        // zone, an NPE, ...) releases those ACTIVE claims — otherwise they orphan the (member,start) tuples
        // permanently (the reaper only recovers BOOKING rows, and the outer catch reverts status but cannot
        // see `inserted`). The known disposition-managed exceptions rethrow as-is (they already released/marked).
        PanelBookingResult result;
        ZoneId zone;
        try {
            zone = ZoneId.of(slot.getZoneId());
            EventDetails details = new EventDetails(template.getName(), req.getLocationText(),
                slot.getStart(), slot.getEnd(), zone);
            List<Participant> panel = participants.stream()
                .map(m -> new Participant(m, null)).toList(); // null zone -> EventDetails (slot) zone
            result = calendar.createPanelEvents(req.getWorkspaceId(), req.getId(), panel, details);
        } catch (RuntimeException unexpected) {
            releaseClaims(inserted, now);
            throw unexpected; // the outer confirm() catch reverts BOOKING -> PENDING_SELECTION
        }

        switch (result.outcome()) {
            case CREATED -> {
                mongo.findAndModify(
                    Query.query(Criteria.where("_id").is(req.getId()).and("status").is(SchedulingStatus.BOOKING)),
                    new Update().set("status", SchedulingStatus.BOOKED).set("bookedAt", now).set("updatedAt", now)
                        .set("lastOutcomeReason", SchedulingOutcomeReason.NONE),
                    SchedulingRequest.class);
                audit.record(AuthEventType.SCHEDULING_BOOKED, req.getWorkspaceId(), "CANDIDATE", "booked", null);
                sendConfirmations(req, template, slot, zone, now);
                log.info("scheduling booked {} {}",
                    StructuredArguments.kv("schedulingRequestId", req.getId()),
                    StructuredArguments.kv("participantCount", participants.size()));
                return new ConfirmResult(slot.getStart(), slot.getZoneId());
            }
            case ROLLED_BACK -> {
                releaseClaims(inserted, now);
                markRequest(req.getId(), SchedulingStatus.PENDING_SELECTION,
                    SchedulingOutcomeReason.BOOKING_FAILED, now, false);
                audit.record(AuthEventType.SCHEDULING_ROLLED_BACK, req.getWorkspaceId(), "CANDIDATE",
                    SchedulingOutcomeReason.BOOKING_FAILED.name(), null);
                throw new SchedulingExceptions.BookingFailedException();
            }
            default -> { // CLEANUP_INCOMPLETE — a known, surfaced orphan (FR-016 honest bound)
                markRequest(req.getId(), SchedulingStatus.CLEANUP_INCOMPLETE,
                    SchedulingOutcomeReason.CLEANUP_INCOMPLETE, now, false);
                audit.record(AuthEventType.SCHEDULING_CLEANUP_INCOMPLETE, req.getWorkspaceId(), "CANDIDATE",
                    SchedulingOutcomeReason.CLEANUP_INCOMPLETE.name(), null);
                notifications.notify(req.getWorkspaceId(), req.getCandidateId(),
                    RecruiterNotificationType.DISPATCH_FAILED);
                log.warn("scheduling cleanup incomplete (orphan may remain) {}",
                    StructuredArguments.kv("schedulingRequestId", req.getId()));
                throw new SchedulingExceptions.CleanupIncompleteException();
            }
        }
    }

    private void staleRefuse(String requestId, Instant now) {
        revertToPending(requestId, now);
        audit.record(AuthEventType.SCHEDULING_REFUSED, requestIdWorkspace(requestId), "CANDIDATE",
            SchedulingOutcomeReason.STALE_SLOT.name(), null);
        throw new SchedulingExceptions.StaleSlotException();
    }

    /** Confirmations: candidate via the consent-gated dispatch; participants via the member-mail path (D7). */
    private void sendConfirmations(SchedulingRequest req, InterviewTemplate template, OfferedSlot slot,
                                   ZoneId zone, Instant now) {
        String date = DATE_FMT.format(slot.getStart().atZone(zone));
        String time = TIME_FMT.format(slot.getStart().atZone(zone));
        String location = req.getLocationText() == null ? "" : req.getLocationText();

        Map<String, String> candidateCtx = new HashMap<>();
        candidateCtx.put("interview_date", date);
        candidateCtx.put("interview_time", time);
        candidateCtx.put("time_zone", slot.getZoneId());
        candidateCtx.put("location", location);
        candidateCtx.put("stage_name", template.getName());
        try {
            dispatch.enqueue(req.getWorkspaceId(), req.getCandidateId(),
                com.cadence.domain.EmailMessageType.CONFIRMATION, "BASE", now, candidateCtx, null);
        } catch (RuntimeException e) {
            // Best-effort: a confirmation failure must NOT roll back the committed booking (FR-018).
            log.warn("candidate confirmation enqueue failed (booking stands) {} {}",
                StructuredArguments.kv("schedulingRequestId", req.getId()),
                StructuredArguments.kv("errorType", e.getClass().getSimpleName()));
        }

        Map<String, String> memberModel = new HashMap<>();
        memberModel.put("title", template.getName());
        memberModel.put("date", date);
        memberModel.put("time", time);
        memberModel.put("timezone", slot.getZoneId());
        memberModel.put("location", location);
        for (String memberId : participantsFromClaims(req.getWorkspaceId(), req.getId())) {
            try {
                emailSender.sendEmail(memberId,
                    com.cadence.integration.OperationalEmailTemplates.INTERVIEW_CONFIRMATION_ID, memberModel);
            } catch (RuntimeException e) {
                log.warn("participant confirmation failed (booking stands) {} {}",
                    StructuredArguments.kv("memberId", memberId),
                    StructuredArguments.kv("errorType", e.getClass().getSimpleName()));
            }
        }
    }

    private List<String> participantsFromClaims(String workspaceId, String requestId) {
        return claims.findByWorkspaceIdAndSchedulingRequestId(workspaceId, requestId).stream()
            .filter(c -> c.getStatus() == com.cadence.domain.ClaimStatus.ACTIVE)
            .map(InterviewSlotClaim::getMemberId).toList();
    }

    private void releaseClaims(List<InterviewSlotClaim> inserted, Instant now) {
        for (InterviewSlotClaim c : inserted) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(c.getId())),
                new Update().set("status", com.cadence.domain.ClaimStatus.RELEASED), InterviewSlotClaim.class);
        }
    }

    private void revertToPending(String requestId, Instant now) {
        mongo.findAndModify(
            Query.query(Criteria.where("_id").is(requestId).and("status").is(SchedulingStatus.BOOKING)),
            new Update().set("status", SchedulingStatus.PENDING_SELECTION).set("updatedAt", now)
                .set("chosenSlotId", null),
            SchedulingRequest.class);
    }

    private void markRequest(String requestId, SchedulingStatus status, SchedulingOutcomeReason reason,
                             Instant now, boolean fromBookingOnly) {
        Criteria c = Criteria.where("_id").is(requestId);
        if (fromBookingOnly) c = c.and("status").is(SchedulingStatus.BOOKING);
        mongo.updateFirst(Query.query(c),
            new Update().set("status", status).set("lastOutcomeReason", reason).set("updatedAt", now),
            SchedulingRequest.class);
    }

    private String requestIdWorkspace(String requestId) {
        return requests.findById(requestId).map(SchedulingRequest::getWorkspaceId).orElse(null);
    }

    private void rateLimit(String ip) {
        if (!rateLimiter.tryAcquire(ip)) {
            throw new SchedulingExceptions.RateLimitedException();
        }
    }

    private static boolean isFree(MemberAvailability ma, Instant from, Instant to) {
        if (ma == null || ma.status() != AvailabilityStatus.DATA) return false;
        for (BusyInterval b : ma.busy()) {
            if (b.start().isBefore(to) && from.isBefore(b.end())) return false;
        }
        return true;
    }

    private static String zoneHint(SchedulingRequest req) {
        return req.getOfferedSlots().isEmpty() ? "UTC" : req.getOfferedSlots().get(0).getZoneId();
    }

    private static Instant chosenStart(SchedulingRequest req) {
        if (req.getChosenSlotId() == null) return null;
        return req.getOfferedSlots().stream()
            .filter(s -> req.getChosenSlotId().equals(s.getSlotId()))
            .map(OfferedSlot::getStart).findFirst().orElse(null);
    }

    private static List<SlotProjection> project(List<OfferedSlot> slots) {
        return slots.stream()
            .map(s -> new SlotProjection(s.getSlotId(), s.getStart(), s.getEnd(), s.getZoneId()))
            .toList();
    }
}
