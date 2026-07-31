package com.cadence.service;

import com.cadence.api.RbacExceptions;
import com.cadence.api.SlaNudgeDtos.ActionResponse;
import com.cadence.api.SlaNudgeDtos.CandidateSla;
import com.cadence.api.SlaNudgeDtos.DraftPreviewResponse;
import com.cadence.config.SlaProperties;
import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.CandidateEventType;
import com.cadence.domain.CandidateStatusOutcome;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.ErasureState;
import com.cadence.domain.GatedFeature;
import com.cadence.domain.RecruiterNotificationType;
import com.cadence.domain.RenderedMessage;
import com.cadence.domain.SlaDraftStatus;
import com.cadence.domain.SlaNudgeDraft;
import com.cadence.domain.SlaState;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.repository.CandidateRepository;
import com.cadence.repository.SlaNudgeDraftRepository;
import com.cadence.repository.WorkspaceConfigRepository;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * F31 SLA Nudge Engine service — classify candidates green/amber/red, scan-create a holding draft per
 * breaching candidate, and let a recruiter preview / approve / dismiss it. <b>Draft-for-approval only</b>:
 * the scan NEVER sends; only {@link #approve} enqueues, routed through the consent-gated
 * {@link EmailDispatchService} so the send-time gate cannot be bypassed (FR-010/FR-016/FR-023, research D4/D7).
 *
 * <p>Implements {@link SlaDraftInvalidator} (the erasure cycle-break) and injects {@link CandidateStatusService}
 * LAZILY via {@link ObjectProvider} (the second, independent break — research D8).
 *
 * <p>PII discipline (D14): the draft holds ids/enums/instants only; logs carry ids + {@code .name()} Strings
 * (never an enum to {@code kv}); the decrypted status link materialises only transiently into the merge context.
 */
@Service
public class SlaNudgeService implements SlaDraftInvalidator {

    private static final Logger log = LoggerFactory.getLogger(SlaNudgeService.class);

    private final SlaNudgeDraftRepository drafts;
    private final MongoTemplate mongo;
    private final CandidateRepository candidates;
    private final ContactPermissionGate gate;
    private final EmailDispatchService dispatch;
    private final EmailTemplateService templates;
    private final RecruiterNotificationService notifications;
    private final CandidateAuditService audit;
    private final CandidateActivityService activity;
    private final WorkspaceConfigRepository configs;
    private final ObjectProvider<CandidateStatusService> statusLinkProvider;
    private final SlaProperties props;
    private final java.time.Clock clock;
    private final EntitlementService entitlements;

    public SlaNudgeService(SlaNudgeDraftRepository drafts, MongoTemplate mongo, CandidateRepository candidates,
                           ContactPermissionGate gate, EmailDispatchService dispatch, EmailTemplateService templates,
                           RecruiterNotificationService notifications, CandidateAuditService audit,
                           CandidateActivityService activity, WorkspaceConfigRepository configs,
                           @Lazy ObjectProvider<CandidateStatusService> statusLinkProvider,
                           SlaProperties props, java.time.Clock clock, EntitlementService entitlements) {
        this.drafts = drafts;
        this.mongo = mongo;
        this.candidates = candidates;
        this.gate = gate;
        this.dispatch = dispatch;
        this.templates = templates;
        this.notifications = notifications;
        this.audit = audit;
        this.activity = activity;
        this.configs = configs;
        this.statusLinkProvider = statusLinkProvider;
        this.props = props;
        this.clock = clock;
        this.entitlements = entitlements;
    }

    // ===================================== classification (US2) ==========================================

    /**
     * Green/amber/red against the silence window (data-model section 5). {@code Duration.ofDays} is absolute,
     * so a DST change in the workspace zone cannot flap the boundary (SC-009 — deterministic under a controlled
     * clock). Erased and terminal-outcome candidates are never surfaced as silence (FR-008/FR-020).
     */
    static SlaState classify(Instant lastContactAt, Instant createdAt, CandidateStatusOutcome outcome,
                             ErasureState erasureState, int windowDays, int amberMarginDays, Instant now) {
        if (erasureState != ErasureState.ACTIVE) {
            return SlaState.GREEN;
        }
        if (outcome == CandidateStatusOutcome.COMPLETE_OFFER || outcome == CandidateStatusOutcome.COMPLETE_REJECTED) {
            return SlaState.GREEN;
        }
        Instant basis = lastContactAt != null ? lastContactAt : createdAt;
        if (basis == null) {
            return SlaState.GREEN; // fail-safe — never spuriously breach
        }
        Instant breachCutoff = now.minus(Duration.ofDays(windowDays));
        Instant amberCutoff = now.minus(Duration.ofDays(Math.max(0, windowDays - amberMarginDays)));
        if (basis.isBefore(breachCutoff)) {
            return SlaState.RED;
        }
        if (basis.isBefore(amberCutoff)) {
            return SlaState.AMBER;
        }
        return SlaState.GREEN;
    }

    private SlaState classify(Candidate c, int windowDays, Instant now) {
        return classify(c.getLastContactAt(), c.getCreatedAt(), c.getStatusOutcome(), c.getErasureState(),
            windowDays, props.getAmberMarginDays(), now);
    }

    /**
     * F51 reuse seam: classify an ALREADY-LOADED candidate against the workspace SLA window with NO extra query
     * (every classifier input is on the candidate doc). The pipeline calls this so its SLA colour is byte-identical
     * to the dashboard/silence-list verdict (FR-004) — it delegates to the same {@link #classify(Candidate, int, Instant)}
     * with the same amber margin, so the two can never drift.
     */
    public SlaState classifyCandidate(WorkspaceConfig cfg, Candidate c, Instant now) {
        return classify(c, effectiveWindowDays(cfg), now);
    }

    private int effectiveWindowDays(WorkspaceConfig cfg) {
        int w = cfg == null ? 0 : cfg.getSlaSilenceWindowDays();
        return w > 0 ? w : props.getDefaultWindowDays();
    }

    private int effectiveWindowDays(String workspaceId) {
        return effectiveWindowDays(configs.findByWorkspaceId(workspaceId).orElse(null));
    }

    /** Per-candidate SLA (contract B). Scoped read — missing/cross-workspace -> indistinguishable 404. */
    public CandidateSla candidateSla(String workspaceId, String candidateId) {
        Candidate c = candidates.findByWorkspaceIdAndId(workspaceId, candidateId)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        Instant now = Instant.now(clock);
        SlaState state = classify(c, effectiveWindowDays(workspaceId), now);
        String openDraftId = drafts.findFirstByWorkspaceIdAndCandidateIdAndStatus(
            workspaceId, candidateId, SlaDraftStatus.OPEN).map(SlaNudgeDraft::getId).orElse(null);
        return new CandidateSla(candidateId, state, c.getLastContactAt(), openDraftId);
    }

    /**
     * The workspace silence list (contract A): AMBER + RED only. Reads the WIDER {@code amberCutoff} range
     * (AMBER rows are NOT past the breach cutoff, so the drafting query would miss them — research D5), then
     * classifies each in Java and joins the open-draft id.
     */
    public List<CandidateSla> silenceList(String workspaceId) {
        Instant now = Instant.now(clock);
        int window = effectiveWindowDays(workspaceId);
        Instant amberCutoff = now.minus(Duration.ofDays(Math.max(0, window - props.getAmberMarginDays())));
        Map<String, String> openDraftByCandidate = new HashMap<>();
        for (SlaNudgeDraft d : drafts.findByWorkspaceIdAndStatus(workspaceId, SlaDraftStatus.OPEN)) {
            openDraftByCandidate.put(d.getCandidateId(), d.getId());
        }
        List<CandidateSla> out = new java.util.ArrayList<>();
        for (Candidate c : candidates.findByWorkspaceIdAndErasureStateAndLastContactAtBefore(
                workspaceId, ErasureState.ACTIVE, amberCutoff, PageRequest.of(0, props.getScanBatchLimit()))) {
            SlaState state = classify(c, window, now);
            if (state != SlaState.GREEN) {
                out.add(new CandidateSla(c.getId(), state, c.getLastContactAt(),
                    openDraftByCandidate.get(c.getId())));
            }
        }
        return out;
    }

    // ===================================== scan / draft (US3) ============================================

    /**
     * One workspace's breach scan (contract H). Skips unconfigured workspaces; reads the paginated, index-backed
     * breach range; per candidate suppresses on the consent gate (FR-019) and the terminal-stage guardrail
     * (FR-020); inserts an OPEN draft (DuplicateKey = idempotent no-op — FR-014/FR-015) and notifies the
     * recruiter (workspace-scoped — FR-012). It has NO send path (SC-008).
     */
    public void scanWorkspace(WorkspaceConfig cfg, Instant now) {
        if (cfg == null || !cfg.isConfigured()) {
            return;
        }
        String ws = cfg.getWorkspaceId();
        if (!entitlements.hasFeature(ws, GatedFeature.SLA_NUDGES)) {
            return; // 032 T7 placement 5: a FREE workspace does not INITIATE new drafts; approve() stays ungated.
        }
        int window = effectiveWindowDays(cfg);
        Instant breachCutoff = now.minus(Duration.ofDays(window));
        List<Candidate> breaching = candidates.findByWorkspaceIdAndErasureStateAndLastContactAtBefore(
            ws, ErasureState.ACTIVE, breachCutoff, PageRequest.of(0, props.getScanBatchLimit()));
        int created = 0;
        for (Candidate c : breaching) {
            if (!gate.evaluate(ws, c.getId()).permit()) {
                continue; // FR-019 suppression (erased/withdrawn/over-retention/no-basis/undeliverable)
            }
            if (c.getStatusOutcome() == CandidateStatusOutcome.COMPLETE_OFFER
                || c.getStatusOutcome() == CandidateStatusOutcome.COMPLETE_REJECTED) {
                continue; // FR-020 stage-aware guardrail
            }
            if (createDraft(ws, c.getId(), now)) {
                created++;
            }
        }
        if (created > 0) {
            log.info("sla nudge scan created drafts {} {}",
                StructuredArguments.kv("workspaceId", ws), StructuredArguments.kv("created", created));
        }
    }

    /** Insert one OPEN draft + notify; returns true on insert, false if an OPEN draft already exists. */
    private boolean createDraft(String workspaceId, String candidateId, Instant now) {
        SlaNudgeDraft d = new SlaNudgeDraft();
        d.setWorkspaceId(workspaceId);
        d.setCandidateId(candidateId);
        d.setStatus(SlaDraftStatus.OPEN);
        d.setMessageType(EmailMessageType.SLA_HOLDING);
        d.setDetectedAt(now);
        try {
            drafts.insert(d);
        } catch (DuplicateKeyException e) {
            return false; // an OPEN draft already exists — idempotent no-op (FR-014/FR-015)
        }
        // FR-012: workspace-scoped notification (no per-candidate recruiter assignment exists in the MVP model —
        // research D11 — so any active Admin/Recruiter sees it; the no-assignee fallback is inherent).
        notifications.notify(workspaceId, candidateId, RecruiterNotificationType.SLA_DRAFT_PENDING);
        return true;
    }

    // ===================================== preview / approve / dismiss (US3) =============================

    /**
     * Render the OPEN draft's holding message for recruiter preview (contract C). Requires an OPEN draft (else
     * scoped 404). Renders {@code SLA_HOLDING} with the candidate's merge fields via the F21 path (missing fields
     * surface as {@code [[missing:...]]} + {@code missingFields}). The controller sets {@code Cache-Control:
     * no-store}; the rendered output is never logged (FR-013/FR-024).
     */
    public DraftPreviewResponse previewDraft(String workspaceId, String candidateId) {
        drafts.findFirstByWorkspaceIdAndCandidateIdAndStatus(workspaceId, candidateId, SlaDraftStatus.OPEN)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        Map<String, String> ctx = mergeContext(workspaceId, candidateId);
        RenderedMessage m = templates.renderForSend(workspaceId, EmailMessageType.SLA_HOLDING, "BASE", candidateId, ctx);
        return new DraftPreviewResponse(EmailMessageType.SLA_HOLDING, m.subject(), m.bodyHtml(), m.missingFields());
    }

    /**
     * Approve (contract D): CAS {@code {_id,workspaceId,status:OPEN} -> APPROVED} (the primary single-winner
     * guard; a loser is {@code matchedCount==0} -> ALREADY_ACTIONED). On win: advance lastContactAt (site 5 —
     * clears the breach immediately, closing the re-draft window) then, in ONE try/catch (the F30 precedent),
     * resolve the status link and enqueue {@code SLA_HOLDING} through the consent-gated channel; a thrown
     * {@code ScopedNotFoundException} (erased candidate) propagates to the indistinguishable 404 (no send).
     * There is NO synchronous {@code REFUSED_AT_SEND} — a since-ineligible (withdrawn/over-retention/undeliverable)
     * candidate is refused asynchronously on the {@code emailDispatches} row (FR-023, authoritative).
     */
    public ActionResponse approve(String workspaceId, String draftId, String actorMemberId) {
        SlaNudgeDraft existing = drafts.findById(draftId)
            .filter(d -> workspaceId.equals(d.getWorkspaceId()))
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        Instant now = Instant.now(clock);
        SlaNudgeDraft won = mongo.findAndModify(
            Query.query(Criteria.where("_id").is(draftId).and("workspaceId").is(workspaceId)
                .and("status").is(SlaDraftStatus.OPEN)),
            new Update().set("status", SlaDraftStatus.APPROVED).set("actionedAt", now)
                .set("actorMemberId", actorMemberId),
            FindAndModifyOptions.options().returnNew(true), SlaNudgeDraft.class);
        if (won == null) {
            return new ActionResponse(draftId, "ALREADY_ACTIONED");
        }
        String candidateId = existing.getCandidateId();
        activity.advanceLastContact(workspaceId, candidateId, now); // site 5 (clears breach)
        // statusLinkFor throws ScopedNotFoundException for an erased candidate -> propagates to 404 (no send).
        String link = statusLinkProvider.getObject().statusLinkFor(workspaceId, candidateId);
        Map<String, String> ctx = mergeContext(workspaceId, candidateId, link);
        dispatch.enqueue(workspaceId, candidateId, EmailMessageType.SLA_HOLDING, "BASE", now, ctx, candidateId);
        audit.append(workspaceId, candidateId, CandidateEventType.SLA_DRAFT_APPROVED,
            CandidateAuditOutcome.RECORDED, actorMemberId);
        log.info("sla draft approved {} {}", StructuredArguments.kv("workspaceId", workspaceId),
            StructuredArguments.kv("draftId", draftId));
        return new ActionResponse(draftId, "SENT_ENQUEUED");
    }

    /** Dismiss (contract E): CAS OPEN -> DISMISSED; sends nothing; audits. Not-OPEN -> ALREADY_ACTIONED. */
    public ActionResponse dismiss(String workspaceId, String draftId, String actorMemberId) {
        SlaNudgeDraft existing = drafts.findById(draftId)
            .filter(d -> workspaceId.equals(d.getWorkspaceId()))
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        Instant now = Instant.now(clock);
        SlaNudgeDraft won = mongo.findAndModify(
            Query.query(Criteria.where("_id").is(draftId).and("workspaceId").is(workspaceId)
                .and("status").is(SlaDraftStatus.OPEN)),
            new Update().set("status", SlaDraftStatus.DISMISSED).set("actionedAt", now)
                .set("actorMemberId", actorMemberId),
            FindAndModifyOptions.options().returnNew(true), SlaNudgeDraft.class);
        if (won == null) {
            return new ActionResponse(draftId, "ALREADY_ACTIONED");
        }
        audit.append(workspaceId, existing.getCandidateId(), CandidateEventType.SLA_DRAFT_DISMISSED,
            CandidateAuditOutcome.RECORDED, actorMemberId);
        log.info("sla draft dismissed {} {}", StructuredArguments.kv("workspaceId", workspaceId),
            StructuredArguments.kv("draftId", draftId));
        return new ActionResponse(draftId, "DISMISSED");
    }

    // ===================================== erasure hook (US3) ============================================

    /** {@link SlaDraftInvalidator}: best-effort CAS OPEN -> INVALIDATED inside the erasure wipe (FR-021). */
    @Override
    public void invalidateOpenDraft(String workspaceId, String candidateId) {
        try {
            mongo.findAndModify(
                Query.query(Criteria.where("workspaceId").is(workspaceId).and("candidateId").is(candidateId)
                    .and("status").is(SlaDraftStatus.OPEN)),
                new Update().set("status", SlaDraftStatus.INVALIDATED).set("actionedAt", Instant.now(clock)),
                SlaNudgeDraft.class);
        } catch (RuntimeException e) {
            // Best-effort only — the authoritative no-message guard is the send-time gate (FR-023).
            log.warn("sla draft invalidate on erasure failed {} {}",
                StructuredArguments.kv("workspaceId", workspaceId),
                StructuredArguments.kv("errorType", e.getClass().getSimpleName()));
        }
    }

    // ===================================== helpers ======================================================

    private Map<String, String> mergeContext(String workspaceId, String candidateId) {
        // statusLinkFor throws ScopedNotFoundException for an erased candidate (preview of an erased candidate's
        // draft cannot occur — an OPEN draft for an erased candidate is best-effort INVALIDATED, and the gate
        // would refuse anyway).
        return mergeContext(workspaceId, candidateId, statusLinkProvider.getObject().statusLinkFor(workspaceId, candidateId));
    }

    private Map<String, String> mergeContext(String workspaceId, String candidateId, String statusLink) {
        Map<String, String> ctx = new HashMap<>();
        ctx.put("status_link", statusLink);
        candidates.findByWorkspaceIdAndId(workspaceId, candidateId)
            .map(Candidate::getStatusExpectedDate)
            .ifPresent(d -> ctx.put("expected_date", d.toString()));
        return ctx;
    }
}
