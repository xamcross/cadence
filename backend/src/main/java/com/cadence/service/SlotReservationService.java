package com.cadence.service;

import com.cadence.api.RbacExceptions;
import com.cadence.api.SchedulingExceptions;
import com.cadence.config.AuthProperties;
import com.cadence.config.SchedulingProperties;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.BusyInterval;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.CandidateEventType;
import com.cadence.domain.ComputedSlot;
import com.cadence.domain.EventDetails;
import com.cadence.domain.InterviewSlotClaim;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.MemberAvailability;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.PanelBookingResult;
import com.cadence.domain.Participant;
import com.cadence.domain.PoolRule;
import com.cadence.domain.SchedulingMode;
import com.cadence.domain.SchedulingOutcomeReason;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.domain.SlotComputationRequest;
import com.cadence.domain.SlotComputationResult;
import com.cadence.domain.RecruiterNotificationType;
import com.cadence.repository.InterviewSlotClaimRepository;
import com.cadence.repository.InterviewTemplateRepository;
import com.cadence.repository.SchedulingRequestRepository;
import com.cadence.security.SecureTokens;
import com.cadence.security.TokenHasher;
import org.springframework.data.domain.PageRequest;
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
    private final CandidateAuditService candidateAudit;
    private final RecruiterNotificationService notifications;
    private final CandidateRateLimiter rateLimiter;
    private final RuleEngine ruleEngine;
    private final SchedulingProperties props;
    private final AuthProperties authProps;
    private final TokenHasher hasher;
    private final MongoTemplate mongo;
    private final Clock clock;
    private final CandidateStatusService statusService;
    private final CandidateActivityService activity;

    public SlotReservationService(SchedulingRequestRepository requests, InterviewSlotClaimRepository claims,
                                  InterviewTemplateRepository templates, ContactPermissionGate gate,
                                  AvailabilityService availability, CalendarEventService calendar,
                                  EmailDispatchService dispatch, com.cadence.integration.EmailSender emailSender,
                                  AuthAuditService audit, CandidateAuditService candidateAudit,
                                  RecruiterNotificationService notifications, CandidateRateLimiter rateLimiter,
                                  RuleEngine ruleEngine, SchedulingProperties props, AuthProperties authProps,
                                  TokenHasher hasher, MongoTemplate mongo, Clock clock,
                                  CandidateStatusService statusService, CandidateActivityService activity) {
        this.requests = requests;
        this.claims = claims;
        this.templates = templates;
        this.gate = gate;
        this.availability = availability;
        this.calendar = calendar;
        this.dispatch = dispatch;
        this.emailSender = emailSender;
        this.audit = audit;
        this.candidateAudit = candidateAudit;
        this.notifications = notifications;
        this.rateLimiter = rateLimiter;
        this.ruleEngine = ruleEngine;
        this.props = props;
        this.authProps = authProps;
        this.hasher = hasher;
        this.mongo = mongo;
        this.clock = clock;
        this.statusService = statusService;
        this.activity = activity;
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

        // F20 same-time no-op (FR-027): a reschedule confirm resolving to the parent's currently-booked
        // instant is an idempotent no-op — abandon this round (SUPERSEDED, so it never counts toward the cap),
        // and return the existing booking. MUST run BEFORE any claims.insert: otherwise we would insert an
        // ACTIVE claim on the parent's own {member,startAt} and self-collide -> a false "slot_taken".
        if (req.getMode() == SchedulingMode.RESCHEDULE && req.getParentRequestId() != null) {
            SchedulingRequest parent = requests.findById(req.getParentRequestId()).orElse(null);
            if (parent != null && parent.getStatus() == SchedulingStatus.BOOKED) {
                Instant parentStart = chosenStart(parent);
                if (parentStart != null && parentStart.equals(slot.getStart())) {
                    markRequest(req.getId(), SchedulingStatus.SUPERSEDED, SchedulingOutcomeReason.NONE, now, false);
                    return new ConfirmResult(parentStart, zoneHint(parent));
                }
            }
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
                // Mint the F20 reschedule/cancel manage credential onto the BOOKED row (every booking is
                // manageable). Capture the CAS result (returnNew) so the forward-commit runs ONLY if this
                // confirm actually won the BOOKING->BOOKED transition (a reaper/concurrent path may have).
                String rawManage = SecureTokens.newToken();
                SchedulingRequest booked = mongo.findAndModify(
                    Query.query(Criteria.where("_id").is(req.getId()).and("status").is(SchedulingStatus.BOOKING)),
                    new Update().set("status", SchedulingStatus.BOOKED).set("bookedAt", now).set("updatedAt", now)
                        .set("manageTokenHash", hasher.hashToken(rawManage))
                        // F23: denormalize the interview start so the no-show cascade can sweep BOOKED rows by
                        // time (the start otherwise lives only inside offeredSlots — D2). Covers initial + reschedule.
                        .set("bookedStartAt", slot.getStart())
                        .set("lastOutcomeReason", SchedulingOutcomeReason.NONE),
                    FindAndModifyOptions.options().returnNew(true), SchedulingRequest.class);
                if (booked == null) {
                    // Lost the BOOKING->BOOKED CAS (a concurrent supersede flipped this round). Clean up the
                    // events + claims this confirm already created so they never orphan, then refuse (no
                    // double-commit, no leak — review B1).
                    releaseClaims(inserted, now);
                    calendar.cancelBooking(req.getWorkspaceId(), req.getId());
                    throw new SchedulingExceptions.SlotTakenException();
                }
                // F31 (research D1, sites 3+4): a booking/reschedule commit is a qualifying activity — advance
                // the canonical last-meaningful-activity instant (clears SLA silence). Covers initial + reschedule
                // (a reschedule confirm also wins this BOOKING->BOOKED CAS for the new round).
                activity.advanceLastContact(booked.getWorkspaceId(), booked.getCandidateId(), now);
                if (req.getMode() == SchedulingMode.RESCHEDULE) {
                    if (!forwardCommitParent(booked, now)) {
                        // The parent was concurrently cancelled/superseded (a cancel raced this confirm). The
                        // reschedule MUST NOT stand — roll the new round back so the booking stays cancelled.
                        calendar.cancelBooking(booked.getWorkspaceId(), booked.getId());
                        releaseClaims(inserted, now);
                        markRequest(booked.getId(), SchedulingStatus.SUPERSEDED, SchedulingOutcomeReason.NONE, now, false);
                        throw new SchedulingExceptions.NotAvailableException();
                    }
                    audit.record(AuthEventType.SCHEDULING_RESCHEDULED, req.getWorkspaceId(), "CANDIDATE",
                        "rescheduled", null);
                    candidateAudit.append(req.getWorkspaceId(), req.getCandidateId(),
                        CandidateEventType.BOOKING_CHANGED, CandidateAuditOutcome.BOOKING_RESCHEDULED, "CANDIDATE");
                } else {
                    audit.record(AuthEventType.SCHEDULING_BOOKED, req.getWorkspaceId(), "CANDIDATE", "booked", null);
                }
                sendConfirmations(booked, template, slot, zone, now, rawManage);
                log.info("scheduling booked {} {} {}",
                    StructuredArguments.kv("schedulingRequestId", req.getId()),
                    StructuredArguments.kv("mode", req.getMode().name()),
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
                                   ZoneId zone, Instant now, String rawManageToken) {
        String date = DATE_FMT.format(slot.getStart().atZone(zone));
        String time = TIME_FMT.format(slot.getStart().atZone(zone));
        String location = req.getLocationText() == null ? "" : req.getLocationText();

        Map<String, String> candidateCtx = new HashMap<>();
        candidateCtx.put("interview_date", date);
        candidateCtx.put("interview_time", time);
        candidateCtx.put("time_zone", slot.getZoneId());
        candidateCtx.put("location", location);
        candidateCtx.put("stage_name", template.getName());
        // F20: the booking-manage (reschedule/cancel) link rides the CONFIRMATION's {{reschedule_link}} token
        // (permitted for CONFIRMATION in MergeTokenCatalogue). TLS body only — never persisted/logged.
        candidateCtx.put("reschedule_link", manageLink(rawManageToken));
        try {
            // F30 (D9): the candidate's lifecycle status-page link rides the CONFIRMATION {{status_link}} token
            // (permitted for CONFIRMATION in MergeTokenCatalogue). statusLinkFor provisions the token via an
            // atomic CAS; a failure is caught below so the booking still stands (FR-018). TLS body only.
            candidateCtx.put("status_link", statusService.statusLinkFor(req.getWorkspaceId(), req.getCandidateId()));
            // scheduledFor=now (the commit instant) discriminates reschedule rounds on the F22 idempotency key
            // (each round commits at a distinct instant — FR-014); tests advance the clock between rounds.
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

    // ================================ F20 Reschedule & Cancellation ================================

    /** Committed reschedule-round states (every state a RESCHEDULE round can reach AFTER it booked). */
    private static final List<SchedulingStatus> COMMITTED_RESCHEDULES = List.of(
        SchedulingStatus.BOOKED, SchedulingStatus.RESCHEDULED, SchedulingStatus.CANCELLING,
        SchedulingStatus.CANCELLED, SchedulingStatus.CLEANUP_INCOMPLETE);

    public record BookingView(String status, Instant bookedStart, String zoneId, Instant at,
                              boolean canReschedule, boolean canCancel, int rescheduleRemaining) {}

    public record OpenRescheduleResult(String rescheduleToken, String zoneHint, List<SlotProjection> slots) {}

    public record CancelResult(Instant at, boolean cleanupIncomplete) {}

    private record OpenedRound(SchedulingRequest round, String rawToken) {}

    /** Token-gated current-booking view (times only) + capabilities (FR-020). 410 for a past interview. */
    public BookingView viewBooking(String rawManageToken, String ip) {
        rateLimit(ip);
        SchedulingRequest b = requests.findByManageTokenHash(hasher.hashToken(rawManageToken))
            .orElseThrow(SchedulingExceptions.TokenInvalidException::new);
        Instant now = Instant.now(clock);
        return switch (b.getStatus()) {
            case BOOKED -> {
                Instant start = chosenStart(b);
                if (start != null && !start.isAfter(now)) {
                    throw new SchedulingExceptions.TokenExpiredException(); // interview in the past -> distinct 410
                }
                boolean eligible = isManageEligible(b, now);
                int remaining = rescheduleRemaining(b);
                yield new BookingView("booked", start, zoneHint(b), null,
                    eligible && remaining > 0, eligible, remaining);
            }
            case CANCELLING, CANCELLED ->
                new BookingView("cancelled", null, zoneHint(b), b.getCancelledAt(), false, false, 0);
            case RESCHEDULED ->
                new BookingView("rescheduled", null, zoneHint(b), b.getUpdatedAt(), false, false, 0);
            // PENDING_SELECTION/BOOKING/EXPIRED/SUPERSEDED never hold a manage token (minted only on BOOKED).
            default -> throw new SchedulingExceptions.TokenInvalidException();
        };
    }

    /** Open a reschedule round (compute fresh slots carving out the moved booking) — returns times only. */
    public OpenRescheduleResult openReschedule(String rawManageToken, String ip) {
        rateLimit(ip);
        Instant now = Instant.now(clock);
        SchedulingRequest booking = requests.findByManageTokenHash(hasher.hashToken(rawManageToken))
            .orElseThrow(SchedulingExceptions.TokenInvalidException::new);
        if (booking.getStatus() != SchedulingStatus.BOOKED) {
            throw new SchedulingExceptions.TokenInvalidException();
        }
        Instant start = chosenStart(booking);
        if (start != null && !start.isAfter(now)) {
            throw new SchedulingExceptions.TokenExpiredException(); // past -> 410
        }
        if (!isManageEligible(booking, now)) {
            throw new SchedulingExceptions.IneligibleException();
        }
        if (rescheduleRemaining(booking) <= 0) {
            capReached(booking, now);                    // invalidate link + notify recruiter + audit (FR-005)
            throw new SchedulingExceptions.CapReachedException();
        }
        if (!gate.evaluate(booking.getWorkspaceId(), booking.getCandidateId()).permit()) {
            throw new SchedulingExceptions.NotAvailableException(); // byte-identical across deny reasons (FR-016)
        }
        OpenedRound r = openRescheduleRound(booking, now);
        return new OpenRescheduleResult(r.rawToken(), zoneHint(r.round()), project(r.round().getOfferedSlots()));
    }

    /** Candidate-initiated cancellation (affirmative POST). */
    public CancelResult cancel(String rawManageToken, String ip) {
        rateLimit(ip);
        SchedulingRequest booking = requests.findByManageTokenHash(hasher.hashToken(rawManageToken))
            .orElseThrow(SchedulingExceptions.TokenInvalidException::new);
        return cancelByBooking(booking, true, "CANDIDATE");
    }

    /**
     * The shared cancel saga (candidate + recruiter, FR-012). CAS BOOKED->CANCELLING (single-winner vs a
     * concurrent reschedule/cancel) -> remove events -> release claims -> CANCELLED (or CLEANUP_INCOMPLETE) ->
     * notify (candidate-initiated -> recruiter in-app; recruiter-initiated -> candidate consent-gated). The
     * manage token is KEPT so the candidate's honest-closure view + an idempotent replay still resolve.
     */
    public CancelResult cancelByBooking(SchedulingRequest booking, boolean candidateInitiated, String actor) {
        Instant now = Instant.now(clock);
        if (booking.getStatus() == SchedulingStatus.CANCELLED) {
            return new CancelResult(booking.getCancelledAt(), false);              // idempotent replay (FR-015)
        }
        if (booking.getStatus() == SchedulingStatus.CLEANUP_INCOMPLETE) {
            return new CancelResult(booking.getCancelledAt() != null ? booking.getCancelledAt() : now, true);
        }
        if (booking.getStatus() != SchedulingStatus.BOOKED) {
            throw new SchedulingExceptions.NoActiveBookingException();
        }
        Instant start = chosenStart(booking);
        if (start != null && !start.isAfter(now)) {
            throw new SchedulingExceptions.IneligibleException();                  // past -> no online change
        }
        if (!isManageEligible(booking, now)) {
            throw new SchedulingExceptions.IneligibleException();
        }
        SchedulingRequest cancelling = mongo.findAndModify(
            Query.query(Criteria.where("_id").is(booking.getId()).and("status").is(SchedulingStatus.BOOKED)),
            new Update().set("status", SchedulingStatus.CANCELLING).set("updatedAt", now),
            FindAndModifyOptions.options().returnNew(true), SchedulingRequest.class);
        if (cancelling == null) {
            SchedulingRequest cur = requests.findById(booking.getId()).orElse(null);
            if (cur != null && cur.getStatus() == SchedulingStatus.CANCELLED) {
                return new CancelResult(cur.getCancelledAt(), false);
            }
            throw new SchedulingExceptions.NoActiveBookingException();             // just rescheduled/cancelled
        }
        boolean clean = calendar.cancelBooking(booking.getWorkspaceId(), booking.getId());
        releaseClaims(booking.getWorkspaceId(), booking.getId(), now);
        SchedulingStatus terminal = clean ? SchedulingStatus.CANCELLED : SchedulingStatus.CLEANUP_INCOMPLETE;
        mongo.updateFirst(Query.query(Criteria.where("_id").is(booking.getId())),
            new Update().set("status", terminal).set("cancelledAt", now).set("updatedAt", now)
                .set("calendarTeardownPending", false)
                .set("lastOutcomeReason", clean ? SchedulingOutcomeReason.NONE : SchedulingOutcomeReason.CLEANUP_INCOMPLETE),
            SchedulingRequest.class);
        audit.record(AuthEventType.SCHEDULING_CANCELLED, booking.getWorkspaceId(), actor, "cancelled", null);
        candidateAudit.append(booking.getWorkspaceId(), booking.getCandidateId(),
            CandidateEventType.BOOKING_CHANGED, CandidateAuditOutcome.BOOKING_CANCELLED, actor);
        if (candidateInitiated) {
            notifications.notify(booking.getWorkspaceId(), booking.getCandidateId(),
                RecruiterNotificationType.INTERVIEW_CANCELLED_BY_CANDIDATE);
        } else {
            sendCancellationToCandidate(booking, now);
        }
        if (!clean) {
            notifications.notify(booking.getWorkspaceId(), booking.getCandidateId(),
                RecruiterNotificationType.CALENDAR_CLEANUP_INCOMPLETE);
            log.warn("scheduling cancel cleanup incomplete (orphan may remain) {}",
                StructuredArguments.kv("schedulingRequestId", booking.getId()));
        }
        return new CancelResult(now, !clean);
    }

    /** Compute (carving out the moved booking, D7) + snapshot + insert a RESCHEDULE round; supersede prior rounds. */
    private OpenedRound openRescheduleRound(SchedulingRequest parent, Instant now) {
        java.time.LocalDate startDate = java.time.LocalDate.now(clock);
        java.time.LocalDate endDate = startDate.plusDays(props.getSearchWindowDays());
        SlotComputationResult result = ruleEngine.compute(new SlotComputationRequest(
            parent.getWorkspaceId(), parent.getTemplateId(), startDate, endDate, parent.getId()));
        Instant bookedStart = chosenStart(parent);
        List<ComputedSlot> fresh = result.slots().stream()
            .filter(s -> bookedStart == null || !s.start().equals(bookedStart)) // exclude the booked instant (FR-006)
            .toList();
        if (fresh.isEmpty()) {
            notifications.notify(parent.getWorkspaceId(), parent.getCandidateId(),
                RecruiterNotificationType.RESCHEDULE_NO_SLOTS);
            throw new SchedulingExceptions.RescheduleNoSlotsException();           // booking retained (FR-007)
        }
        String raw = SecureTokens.newToken();
        SchedulingRequest round = new SchedulingRequest();
        round.setWorkspaceId(parent.getWorkspaceId());
        round.setCandidateId(parent.getCandidateId());
        round.setTemplateId(parent.getTemplateId());
        round.setStatus(SchedulingStatus.PENDING_SELECTION);
        round.setMode(SchedulingMode.RESCHEDULE);
        round.setParentRequestId(parent.getId());
        round.setRootRequestId(parent.getRootRequestId() != null ? parent.getRootRequestId() : parent.getId());
        round.setTokenHash(hasher.hashToken(raw));
        round.setSentAt(now);
        round.setExpiresAt(now.plus(props.getTokenTtl()));
        round.setSearchRangeStart(startDate);
        round.setSearchRangeEnd(endDate);
        round.setOfferedSlots(snapshot(fresh));
        round.setLocationText(parent.getLocationText()); // plaintext copy; converter re-encrypts on insert
        round.setCreatedAt(now);
        round.setUpdatedAt(now);
        SchedulingRequest saved = requests.insert(round);
        // Supersede any prior live reschedule round for this booking — one authoritative session (FR-017b).
        mongo.updateMulti(
            Query.query(Criteria.where("parentRequestId").is(parent.getId()).and("_id").ne(saved.getId())
                .and("status").in(SchedulingStatus.PENDING_SELECTION, SchedulingStatus.BOOKING)),
            new Update().set("status", SchedulingStatus.SUPERSEDED)
                .set("supersededByRequestId", saved.getId()).set("updatedAt", now),
            SchedulingRequest.class);
        return new OpenedRound(saved, raw);
    }

    /**
     * Forward-commit: cancel the OLD booking only after the NEW round committed (D2). Idempotent via the CAS.
     * Returns {@code true} iff the parent CAS matched (parent was still the authoritative BOOKED booking). A
     * {@code false} means the parent was concurrently cancelled/superseded (e.g. a cancel raced this reschedule
     * confirm) — the caller MUST roll the new round back so a cancelled booking is never left with a live
     * reschedule (no split state, SC-004).
     */
    private boolean forwardCommitParent(SchedulingRequest child, Instant now) {
        if (child.getParentRequestId() == null) {
            return true;
        }
        SchedulingRequest parent = mongo.findAndModify(
            Query.query(Criteria.where("_id").is(child.getParentRequestId()).and("status").is(SchedulingStatus.BOOKED)),
            new Update().set("status", SchedulingStatus.RESCHEDULED).set("updatedAt", now).unset("manageTokenHash"),
            FindAndModifyOptions.options().returnNew(true), SchedulingRequest.class);
        if (parent == null) {
            return false;
        }
        calendar.cancelBooking(parent.getWorkspaceId(), parent.getId());
        releaseClaims(parent.getWorkspaceId(), parent.getId(), now);
        return true;
    }

    /** Cap reached (FR-005): invalidate the self-service link, notify the recruiter, audit. Candidate sees the 409. */
    private void capReached(SchedulingRequest booking, Instant now) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(booking.getId())),
            new Update().unset("manageTokenHash").set("updatedAt", now), SchedulingRequest.class);
        notifications.notify(booking.getWorkspaceId(), booking.getCandidateId(),
            RecruiterNotificationType.RESCHEDULE_CAP_REACHED);
        audit.record(AuthEventType.SCHEDULING_CAP_REACHED, booking.getWorkspaceId(), "CANDIDATE", "cap_reached", null);
    }

    /**
     * Recruiter re-invite (D10): rotate the manage token (invalidating the prior link), stamp
     * {@code rescheduleInvitedAt}, supersede any prior live reschedule round, and re-send the CONFIRMATION
     * (which carries the new {@code reschedule_link}). The existing booking stays BOOKED (FR-003).
     */
    public void resendRescheduleInvitation(SchedulingRequest booking) {
        Instant now = Instant.now(clock);
        String rawManage = SecureTokens.newToken();
        SchedulingRequest rotated = mongo.findAndModify(
            Query.query(Criteria.where("_id").is(booking.getId()).and("status").is(SchedulingStatus.BOOKED)),
            new Update().set("manageTokenHash", hasher.hashToken(rawManage))
                .set("rescheduleInvitedAt", now).set("updatedAt", now),
            FindAndModifyOptions.options().returnNew(true), SchedulingRequest.class);
        if (rotated == null) {
            return; // no longer BOOKED (raced)
        }
        mongo.updateMulti(
            Query.query(Criteria.where("parentRequestId").is(booking.getId())
                .and("status").in(SchedulingStatus.PENDING_SELECTION, SchedulingStatus.BOOKING)),
            new Update().set("status", SchedulingStatus.SUPERSEDED).set("updatedAt", now),
            SchedulingRequest.class);
        OfferedSlot slot = rotated.getOfferedSlots().stream()
            .filter(s -> s.getSlotId().equals(rotated.getChosenSlotId())).findFirst().orElse(null);
        InterviewTemplate template = templates
            .findByWorkspaceIdAndId(rotated.getWorkspaceId(), rotated.getTemplateId()).orElse(null);
        if (slot != null && template != null) {
            sendConfirmations(rotated, template, slot, ZoneId.of(slot.getZoneId()), now, rawManage);
        }
    }

    /** Recruiter-initiated cancel notifies the candidate (consent-gated, best-effort — never rolls back the cancel). */
    private void sendCancellationToCandidate(SchedulingRequest booking, Instant now) {
        try {
            dispatch.enqueue(booking.getWorkspaceId(), booking.getCandidateId(),
                com.cadence.domain.EmailMessageType.CANCELLATION, "BASE", now, new HashMap<>(), null);
        } catch (RuntimeException e) {
            log.warn("candidate cancellation notice enqueue failed (cancel stands) {} {}",
                StructuredArguments.kv("schedulingRequestId", booking.getId()),
                StructuredArguments.kv("errorType", e.getClass().getSimpleName()));
        }
    }

    private void releaseClaims(String workspaceId, String requestId, Instant now) {
        mongo.updateMulti(
            Query.query(Criteria.where("workspaceId").is(workspaceId)
                .and("schedulingRequestId").is(requestId).and("status").is(com.cadence.domain.ClaimStatus.ACTIVE)),
            new Update().set("status", com.cadence.domain.ClaimStatus.RELEASED), InterviewSlotClaim.class);
    }

    private boolean isManageEligible(SchedulingRequest b, Instant now) {
        Instant start = chosenStart(b);
        return start != null && now.isBefore(start.minus(props.getSelfServiceLeadTime()));
    }

    private int rescheduleRemaining(SchedulingRequest b) {
        long used = requests.countByRootRequestIdAndModeAndStatusIn(
            b.resolveRootRequestId(), SchedulingMode.RESCHEDULE, COMMITTED_RESCHEDULES);
        return Math.max(0, props.getRescheduleCap() - (int) used);
    }

    private String manageLink(String rawManageToken) {
        return authProps.getSpaBaseUrl() + props.getSpaBookingBasePath() + "?token=" + rawManageToken;
    }

    private List<OfferedSlot> snapshot(List<ComputedSlot> slots) {
        List<OfferedSlot> out = new ArrayList<>(slots.size());
        for (int i = 0; i < slots.size(); i++) {
            ComputedSlot c = slots.get(i);
            OfferedSlot s = new OfferedSlot();
            s.setSlotId(Integer.toString(i));
            s.setStart(c.start());
            s.setEnd(c.end());
            s.setZoneId(c.zoneId());
            s.setRequiredMemberIds(new ArrayList<>(c.requiredMemberIds()));
            int poolCount = c.qualifyingByPoolIndex().keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
            List<List<String>> pools = new ArrayList<>();
            for (int p = 0; p < poolCount; p++) {
                pools.add(new ArrayList<>(c.qualifyingByPoolIndex().getOrDefault(p, List.of())));
            }
            s.setPoolCandidates(pools);
            out.add(s);
        }
        return out;
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
