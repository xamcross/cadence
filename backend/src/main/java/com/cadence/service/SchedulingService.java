package com.cadence.service;

import com.cadence.api.RbacExceptions;
import com.cadence.api.SchedulingExceptions;
import com.cadence.config.AuthProperties;
import com.cadence.config.SchedulingProperties;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.Candidate;
import com.cadence.domain.ComputedSlot;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.SchedulingOutcomeReason;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.domain.SlotComputationRequest;
import com.cadence.domain.SlotComputationResult;
import com.cadence.repository.CandidateRepository;
import com.cadence.repository.InterviewTemplateRepository;
import com.cadence.repository.SchedulingRequestRepository;
import com.cadence.security.SecureTokens;
import com.cadence.security.TokenHasher;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * F13 initiation + recruiter status (contract A). {@link #initiate} gates contactability, computes the
 * compliant slots via the unchanged F12 {@link RuleEngine}, snapshots them onto a new
 * {@link SchedulingRequest} with a hashed token + TTL, supersedes any prior live request for the candidate,
 * and enqueues the consent-gated invitation email (the link in transient context). Value-free logs/audit
 * (never the candidate name, the token, or the location).
 */
@Service
public class SchedulingService {

    private static final Logger log = LoggerFactory.getLogger(SchedulingService.class);

    private final RuleEngine ruleEngine;
    private final InterviewTemplateRepository templates;
    private final CandidateRepository candidates;
    private final ContactPermissionGate gate;
    private final SchedulingRequestRepository requests;
    private final MongoTemplate mongo;
    private final TokenHasher hasher;
    private final EmailDispatchService dispatch;
    private final AuthAuditService audit;
    private final AuthProperties authProps;
    private final SchedulingProperties props;
    private final Clock clock;

    public SchedulingService(RuleEngine ruleEngine, InterviewTemplateRepository templates,
                             CandidateRepository candidates, ContactPermissionGate gate,
                             SchedulingRequestRepository requests, MongoTemplate mongo, TokenHasher hasher,
                             EmailDispatchService dispatch, AuthAuditService audit, AuthProperties authProps,
                             SchedulingProperties props, Clock clock) {
        this.ruleEngine = ruleEngine;
        this.templates = templates;
        this.candidates = candidates;
        this.gate = gate;
        this.requests = requests;
        this.mongo = mongo;
        this.hasher = hasher;
        this.dispatch = dispatch;
        this.audit = audit;
        this.authProps = authProps;
        this.props = props;
        this.clock = clock;
    }

    public record InitiateResult(String schedulingRequestId, SchedulingStatus status, int offeredSlotCount,
                                 Instant sentAt, Instant expiresAt) {}

    public record StatusView(SchedulingStatus status, Instant sentAt, Instant expiresAt, Instant chosenStart) {}

    /** Recruiter-initiated single-stage scheduling (US1). */
    public InitiateResult initiate(String workspaceId, String actorMemberId, String candidateId,
                                   String templateId, String locationText,
                                   LocalDate rangeStart, LocalDate rangeEnd, String ip) {
        Instant now = Instant.now(clock);
        // Workspace-scoped candidate + template existence (empty -> ScopedNotFoundException -> 404, oracle-free).
        candidates.findByWorkspaceIdAndId(workspaceId, candidateId)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        InterviewTemplate template = templates.findByWorkspaceIdAndId(workspaceId, templateId)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);

        // Contactability before any email (FR-004).
        if (!gate.evaluate(workspaceId, candidateId).permit()) {
            audit.record(AuthEventType.SCHEDULING_REFUSED, workspaceId, actorMemberId,
                SchedulingOutcomeReason.NOT_CONTACTABLE.name(), ip);
            throw new SchedulingExceptions.NotContactableException();
        }

        LocalDate start = rangeStart != null ? rangeStart : LocalDate.now(clock);
        LocalDate end = rangeEnd != null ? rangeEnd : start.plusDays(props.getSearchWindowDays());

        // Compute via the unchanged F12 engine (throws ScopedNotFound/TemplateRetired/WorkspaceNotConfigured).
        SlotComputationResult result = ruleEngine.compute(new SlotComputationRequest(workspaceId, templateId, start, end));

        // A required member unschedulable -> refuse and name them (FR-005). (Optional members never gate.)
        if (!result.unschedulable().isEmpty()) {
            List<String> ids = result.unschedulable().stream().map(u -> u.memberId()).sorted().toList();
            audit.record(AuthEventType.SCHEDULING_REFUSED, workspaceId, actorMemberId,
                SchedulingOutcomeReason.UNSCHEDULABLE_REQUIRED.name(), ip);
            throw new SchedulingExceptions.UnschedulableRequiredException(ids);
        }
        if (result.slots().isEmpty()) {
            audit.record(AuthEventType.SCHEDULING_REFUSED, workspaceId, actorMemberId,
                SchedulingOutcomeReason.NO_SLOTS.name(), ip);
            throw new SchedulingExceptions.NoSlotsException();
        }

        String raw = SecureTokens.newToken();
        SchedulingRequest req = new SchedulingRequest();
        req.setWorkspaceId(workspaceId);
        req.setCandidateId(candidateId);
        req.setTemplateId(templateId);
        req.setStatus(SchedulingStatus.PENDING_SELECTION);
        req.setTokenHash(hasher.hashToken(raw));
        req.setSentAt(now);
        req.setExpiresAt(now.plus(props.getTokenTtl()));
        req.setSearchRangeStart(start);
        req.setSearchRangeEnd(end);
        req.setOfferedSlots(snapshot(result.slots()));
        req.setLocationText(locationText); // converter encrypts on insert (no pre-encrypt)
        req.setCreatedAt(now);
        req.setUpdatedAt(now);
        SchedulingRequest saved = insertUnique(req, raw);

        // Re-send supersedes any prior live link for this candidate so only one active booking path exists (FR-022).
        mongo.updateMulti(
            Query.query(Criteria.where("workspaceId").is(workspaceId).and("candidateId").is(candidateId)
                .and("_id").ne(saved.getId())
                .and("status").in(SchedulingStatus.PENDING_SELECTION, SchedulingStatus.BOOKING)),
            new Update().set("status", SchedulingStatus.SUPERSEDED)
                .set("supersededByRequestId", saved.getId()).set("updatedAt", now),
            SchedulingRequest.class);

        // Enqueue the consent-gated invitation; the link rides TRANSIENT nonPiiContext (never persisted).
        // Honest bound: a transiently-failed invitation is recovered by recruiter re-send (new token), not by
        // the outbox retry (which would render [[missing:scheduling_link]] — the context is not persisted).
        String link = authProps.getSpaBaseUrl() + props.getSpaScheduleBasePath() + "?token=" + raw;
        Map<String, String> ctx = new HashMap<>();
        ctx.put("scheduling_link", link);
        ctx.put("stage_name", template.getName());
        ctx.put("time_zone", result.slots().get(0).zoneId());
        try {
            dispatch.enqueue(workspaceId, candidateId, EmailMessageType.INVITATION, "BASE", now, ctx, null);
        } catch (RuntimeException e) {
            // Best-effort: the request row + token are already committed. A transient dispatch failure must
            // not abort initiation (and roll nothing back) — the recruiter re-sends (a re-send mints a new
            // token and supersedes). Never log the link/token (PII); only the error type.
            log.warn("scheduling invitation enqueue failed (link created; recruiter can re-send) {} {}",
                StructuredArguments.kv("schedulingRequestId", saved.getId()),
                StructuredArguments.kv("errorType", e.getClass().getSimpleName()));
        }

        audit.record(AuthEventType.SCHEDULING_LINK_SENT, workspaceId, actorMemberId, "link_sent", ip);
        log.info("scheduling link sent {} {} {}",
            StructuredArguments.kv("schedulingRequestId", saved.getId()),
            StructuredArguments.kv("templateId", templateId),
            StructuredArguments.kv("offeredSlotCount", saved.getOfferedSlots().size()));
        return new InitiateResult(saved.getId(), saved.getStatus(), saved.getOfferedSlots().size(),
            saved.getSentAt(), saved.getExpiresAt());
    }

    /** Latest per-candidate scheduling status (US3). */
    public StatusView status(String workspaceId, String candidateId) {
        SchedulingRequest req = requests
            .findFirstByWorkspaceIdAndCandidateIdOrderByCreatedAtDesc(workspaceId, candidateId)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        Instant chosenStart = null;
        if (req.getStatus() == SchedulingStatus.BOOKED && req.getChosenSlotId() != null) {
            for (OfferedSlot s : req.getOfferedSlots()) {
                if (req.getChosenSlotId().equals(s.getSlotId())) {
                    chosenStart = s.getStart();
                    break;
                }
            }
        }
        return new StatusView(req.getStatus(), req.getSentAt(), req.getExpiresAt(), chosenStart);
    }

    private SchedulingRequest insertUnique(SchedulingRequest req, String raw) {
        try {
            return requests.insert(req);
        } catch (DuplicateKeyException e) {
            // Astronomically unlikely 256-bit token-hash collision — re-mint and retry once.
            req.setTokenHash(hasher.hashToken(SecureTokens.newToken()));
            return requests.insert(req);
        }
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
}
