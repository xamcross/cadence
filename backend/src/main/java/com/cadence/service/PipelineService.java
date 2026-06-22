package com.cadence.service;

import com.cadence.api.PipelineDtos.PipelineRow;
import com.cadence.api.PipelineDtos.PipelinePage;
import com.cadence.api.PipelineDtos.PipelineSchedulingStatus;
import com.cadence.api.PipelineDtos.PipelineSort;
import com.cadence.api.PipelineDtos.PipelineStatusFilter;
import com.cadence.api.PipelineDtos.TimelineEvent;
import com.cadence.api.PipelineDtos.TimelineResponse;
import com.cadence.api.RbacExceptions;
import com.cadence.config.PipelineProperties;
import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditEvent;
import com.cadence.domain.CandidateEventType;
import com.cadence.domain.CandidateStatusOutcome;
import com.cadence.domain.ErasureState;
import com.cadence.domain.FeedbackRequestStatus;
import com.cadence.domain.Requisition;
import com.cadence.domain.RequisitionStatus;
import com.cadence.domain.ResourceType;
import com.cadence.domain.Role;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.domain.SlaState;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.repository.CandidateRepository;
import com.cadence.repository.FeedbackRequestRepository;
import com.cadence.repository.RequisitionRepository;
import com.cadence.repository.SchedulingRequestRepository;
import com.cadence.repository.WorkspaceConfigRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * F51 Pipeline View read service — composes the candidate list (SLA colour + stage + scheduling status) and the
 * per-candidate timeline, with server-side role scoping. Pure orchestration over existing seams: SLA via the F31
 * {@link SlaNudgeService#classifyCandidate} (no N+1 — every input is on the candidate doc); HM scoping via the F02
 * {@link AssignmentService#assignedResourceIds}; scheduling status via one batch read mapped through the pure
 * {@link #mapSchedulingStatus} function; timeline via the F04 candidate {@code auditLog} (PII-free by construction).
 *
 * <p>Compose -> filter -> sort -> paginate happens in memory over a bounded scan ({@code scanCap}); beyond the cap
 * the response is {@code truncated=true} (no silent cap, research D4). PII (decrypted name/stage) is returned to
 * authorized staff only and never logged (FR-024).
 */
@Service
public class PipelineService {

    private final Clock clock;
    private final CandidateRepository candidates;
    private final RequisitionRepository requisitions;
    private final SchedulingRequestRepository scheduling;
    private final SlaNudgeService sla;
    private final AssignmentService assignments;
    private final WorkspaceConfigRepository configs;
    private final CandidateAuditService audit;
    private final FeedbackRequestRepository feedback;
    private final PipelineProperties props;

    public PipelineService(Clock clock, CandidateRepository candidates, RequisitionRepository requisitions,
                           SchedulingRequestRepository scheduling, SlaNudgeService sla, AssignmentService assignments,
                           WorkspaceConfigRepository configs, CandidateAuditService audit,
                           FeedbackRequestRepository feedback, PipelineProperties props) {
        this.clock = clock;
        this.candidates = candidates;
        this.requisitions = requisitions;
        this.scheduling = scheduling;
        this.sla = sla;
        this.assignments = assignments;
        this.configs = configs;
        this.audit = audit;
        this.feedback = feedback;
        this.props = props;
    }

    /** The bundle of pipeline filters (all optional except {@code status}). */
    public record Filters(PipelineStatusFilter status, String requisitionId, SlaState sla,
                          PipelineSchedulingStatus scheduling, String stage) {}

    /** Internal compose holder (carries the terminal flag the {@link PipelineRow} does not need to expose). */
    private record Composed(PipelineRow row, boolean terminal) {}

    // ===================================== list (US1 + US2) =============================================

    public PipelinePage list(String workspaceId, String memberId, Role role, Filters filters,
                             PipelineSort sort, int page, int size) {
        Instant now = Instant.now(clock);
        WorkspaceConfig cfg = configs.findByWorkspaceId(workspaceId).orElse(null);
        int cap = props.getScanCap();
        PageRequest scan = PageRequest.of(0, cap);

        List<Candidate> active;
        long totalInScope;
        if (role == Role.HIRING_MANAGER) {
            // Built scan-time FROM the assigned requisition set (never fetch-all-then-filter). An empty set
            // short-circuits to an empty page (FR-013/FR-014) — never an unfiltered read.
            List<String> reqIds = assignments.assignedResourceIds(workspaceId, memberId, ResourceType.REQUISITION);
            if (reqIds.isEmpty()) {
                return new PipelinePage(List.of(), page, size, 0, 0, false);
            }
            active = candidates.findByWorkspaceIdAndErasureStateAndRequisitionIdIn(
                workspaceId, ErasureState.ACTIVE, reqIds, scan);
            totalInScope = candidates.countByWorkspaceIdAndErasureStateAndRequisitionIdIn(
                workspaceId, ErasureState.ACTIVE, reqIds);
        } else {
            active = candidates.findByWorkspaceIdAndErasureState(workspaceId, ErasureState.ACTIVE, scan);
            totalInScope = candidates.countByWorkspaceIdAndErasureState(workspaceId, ErasureState.ACTIVE);
        }
        boolean truncated = totalInScope > cap;

        // Batch scheduling-status read (one query) + requisition-title resolve (one query) — no per-row fan-out.
        List<String> ids = active.stream().map(Candidate::getId).toList();
        Map<String, SchedulingRequest> resolvedScheduling = resolveScheduling(workspaceId, ids);
        Map<String, Requisition> reqById = new HashMap<>();
        for (Requisition r : requisitions.findByWorkspaceId(workspaceId)) {
            reqById.put(r.getId(), r);
        }

        List<Composed> composed = new ArrayList<>(active.size());
        for (Candidate c : active) {
            SlaState slaState = sla.classifyCandidate(cfg, c, now);          // no query — reuse, no drift (FR-004)
            String stage = resolveStage(c);
            SchedulingRequest sr = resolvedScheduling.get(c.getId());
            PipelineSchedulingStatus ss = mapSchedulingStatus(sr, now);
            Requisition req = c.getRequisitionId() == null ? null : reqById.get(c.getRequisitionId());
            String reqTitle = req == null ? null : req.getTitle();
            boolean terminal = isTerminal(c, req);
            composed.add(new Composed(
                new PipelineRow(c.getId(), c.getName(), stage, slaState, ss,
                    c.getRequisitionId(), reqTitle, c.getLastContactAt()),
                terminal));
        }

        // Filter (default excludes terminal/closed), then sort, then paginate — all in memory (D4).
        List<Composed> filtered = composed.stream().filter(x -> matches(x, filters)).toList();
        List<PipelineRow> sorted = new ArrayList<>(filtered.stream().map(Composed::row).toList());
        sorted.sort(comparator(sort));

        long filteredCount = sorted.size();
        long startIdx = (long) page * size;
        int from = (int) Math.min(startIdx, sorted.size());
        int to = (int) Math.min((long) from + size, sorted.size());
        List<PipelineRow> pageRows = from >= to ? List.of() : new ArrayList<>(sorted.subList(from, to));
        return new PipelinePage(pageRows, page, size, totalInScope, filteredCount, truncated);
    }

    private boolean matches(Composed x, Filters f) {
        if (f.status() != PipelineStatusFilter.INCLUDE_CLOSED && x.terminal()) {
            return false;
        }
        PipelineRow r = x.row();
        if (f.requisitionId() != null && !f.requisitionId().isBlank()
            && !f.requisitionId().equals(r.requisitionId())) {
            return false;
        }
        if (f.sla() != null && f.sla() != r.slaState()) {
            return false;
        }
        if (f.scheduling() != null && f.scheduling() != r.schedulingStatus()) {
            return false;
        }
        if (f.stage() != null && !f.stage().isBlank()) {
            String stage = r.stage() == null ? "" : r.stage().toLowerCase(Locale.ROOT);
            if (!stage.contains(f.stage().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /** Stable comparator: primary by the requested key, tie-broken by most-recent-activity desc then candidateId. */
    private Comparator<PipelineRow> comparator(PipelineSort sort) {
        Comparator<PipelineRow> recent = Comparator
            .comparing((PipelineRow r) -> r.lastActivityAt() == null ? Instant.EPOCH : r.lastActivityAt(),
                Comparator.reverseOrder())
            .thenComparing(PipelineRow::candidateId);
        return switch (sort) {
            case RECENT -> recent;
            case STAGE -> Comparator.comparing((PipelineRow r) -> r.stage() == null ? "" : r.stage(),
                String.CASE_INSENSITIVE_ORDER).thenComparing(recent);
            // RED most urgent first -> reverse the ordinal (GREEN,AMBER,RED).
            case SLA -> Comparator.comparingInt((PipelineRow r) -> -r.slaState().ordinal()).thenComparing(recent);
            case SCHEDULING -> Comparator.comparingInt((PipelineRow r) -> r.schedulingStatus().ordinal())
                .thenComparing(recent);
        };
    }

    /** Best available decrypted stage label, else a defined "Not started" (never a blank/broken cell, FR-001). */
    private String resolveStage(Candidate c) {
        if (notBlank(c.getStatusStage())) return c.getStatusStage();
        if (notBlank(c.getAtsStageLabel())) return c.getAtsStageLabel();
        if (notBlank(c.getImportStageLabel())) return c.getImportStageLabel();
        return "Not started";
    }

    /** Terminal/closed = a completed status outcome OR linked to a CLOSED requisition (FR-003 default exclusion). */
    private boolean isTerminal(Candidate c, Requisition req) {
        CandidateStatusOutcome o = c.getStatusOutcome();
        if (o == CandidateStatusOutcome.COMPLETE_OFFER || o == CandidateStatusOutcome.COMPLETE_REJECTED) {
            return true;
        }
        return req != null && req.getStatus() == RequisitionStatus.CLOSED;
    }

    private Map<String, SchedulingRequest> resolveScheduling(String workspaceId, List<String> candidateIds) {
        Map<String, SchedulingRequest> out = new HashMap<>();
        if (candidateIds.isEmpty()) {
            return out;
        }
        for (SchedulingRequest sr : scheduling.findByWorkspaceIdAndCandidateIdIn(workspaceId, candidateIds)) {
            out.merge(sr.getCandidateId(), sr, PipelineService::preferLiveBookedElseNewest);
        }
        return out;
    }

    /** Resolution: a live BOOKED row wins; otherwise the newest by {@code createdAt} (mirrors SchedulingService.status). */
    private static SchedulingRequest preferLiveBookedElseNewest(SchedulingRequest a, SchedulingRequest b) {
        boolean aBooked = a.getStatus() == SchedulingStatus.BOOKED;
        boolean bBooked = b.getStatus() == SchedulingStatus.BOOKED;
        if (aBooked != bBooked) {
            return aBooked ? a : b;
        }
        Instant ac = a.getCreatedAt() == null ? Instant.EPOCH : a.getCreatedAt();
        Instant bc = b.getCreatedAt() == null ? Instant.EPOCH : b.getCreatedAt();
        return ac.isBefore(bc) ? b : a;
    }

    /**
     * Pure FR-005 mapping from a candidate's resolved scheduling request to its displayed status (single source of
     * truth; unit-tested by {@code PipelineSchedulingStatusTest}). A null request -> NO_LINK_SENT.
     */
    public static PipelineSchedulingStatus mapSchedulingStatus(SchedulingRequest sr, Instant now) {
        if (sr == null || sr.getStatus() == null) {
            return PipelineSchedulingStatus.NO_LINK_SENT;
        }
        return switch (sr.getStatus()) {
            case PENDING_SELECTION -> (sr.getExpiresAt() != null && !sr.getExpiresAt().isAfter(now))
                ? PipelineSchedulingStatus.EXPIRED : PipelineSchedulingStatus.LINK_SENT;
            case BOOKING -> PipelineSchedulingStatus.SLOT_PICKED;
            case BOOKED -> sr.getNoShowAt() != null
                ? PipelineSchedulingStatus.NO_SHOW : PipelineSchedulingStatus.CONFIRMED;
            case RESCHEDULED -> PipelineSchedulingStatus.RESCHEDULED;
            case CANCELLING, CANCELLED -> PipelineSchedulingStatus.CANCELLED;
            case EXPIRED -> PipelineSchedulingStatus.EXPIRED;
            case SUPERSEDED, CLEANUP_INCOMPLETE -> PipelineSchedulingStatus.NO_LINK_SENT;
        };
    }

    // ===================================== timeline (US4) ==============================================

    public TimelineResponse timeline(String workspaceId, String memberId, Role role, String candidateId) {
        resolveScopedCandidateOrNotFound(workspaceId, memberId, role, candidateId);
        List<TimelineEvent> events = new ArrayList<>();
        for (CandidateAuditEvent e : audit.list(workspaceId, candidateId)) {
            events.add(new TimelineEvent(e.getOccurredAt(), e.getEventType().name(), label(e.getEventType())));
        }
        boolean feedbackPending = !feedback.findByWorkspaceIdAndCandidateIdAndStatus(
            workspaceId, candidateId, FeedbackRequestStatus.PENDING).isEmpty();
        return new TimelineResponse(candidateId, events, feedbackPending);
    }

    /**
     * Resolve a candidate with the SAME scoping as the list (FR-022): not-found / erased / out-of-scope-HM all throw
     * the indistinguishable {@link RbacExceptions.ScopedNotFoundException} (no existence oracle). Shared by the
     * timeline (US4) and available to any future per-candidate pipeline read.
     */
    public Candidate resolveScopedCandidateOrNotFound(String workspaceId, String memberId, Role role,
                                                      String candidateId) {
        Candidate c = candidates.findByWorkspaceIdAndId(workspaceId, candidateId)
            .filter(x -> x.getErasureState() == ErasureState.ACTIVE)   // erased -> no residual (FR-007)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        if (role == Role.HIRING_MANAGER) {
            List<String> reqIds = assignments.assignedResourceIds(workspaceId, memberId, ResourceType.REQUISITION);
            if (c.getRequisitionId() == null || !reqIds.contains(c.getRequisitionId())) {
                throw new RbacExceptions.ScopedNotFoundException();
            }
        }
        return c;
    }

    private static String label(CandidateEventType t) {
        return switch (t) {
            case RECORD_CREATED -> "Candidate added";
            case BASIS_RECORDED -> "Contact consent recorded";
            case BASIS_WITHDRAWN -> "Contact consent withdrawn";
            case ERASURE_REQUESTED -> "Erasure requested";
            case ERASURE_REQUEST_CONFIRMED -> "Erasure confirmed";
            case ERASURE_REQUEST_REJECTED -> "Erasure request rejected";
            case ERASURE_COMPLETED -> "Personal data erased";
            case RETENTION_FLAGGED -> "Flagged for retention review";
            case RETENTION_FLAG_CLEARED -> "Retention flag cleared";
            case RETENTION_DELETED -> "Deleted on retention";
            case MESSAGE_SENT -> "Email sent";
            case BOOKING_CHANGED -> "Interview booking changed";
            case STAGE_CHANGED -> "Stage changed";
            case STATUS_PUBLISHED -> "Status updated";
            case STATUS_LINK_ISSUED -> "Status link issued";
            case STATUS_LINK_ROTATED -> "Status link rotated";
            case SLA_DRAFT_APPROVED -> "Holding message sent";
            case SLA_DRAFT_DISMISSED -> "SLA nudge dismissed";
            case SCORECARD_SUBMITTED -> "Feedback submitted";
            case FEEDBACK_INVALIDATED -> "Feedback invalidated";
            case REQUISITION_LINKED -> "Requisition linked";
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
