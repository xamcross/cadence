package com.cadence.service;

import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.CandidateEventType;
import com.cadence.domain.ErasureReasonCode;
import com.cadence.domain.ErasureRequest;
import com.cadence.domain.RequestStatus;
import com.cadence.repository.ErasureRequestRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Candidate-initiated erasure requests (F04, US4). The intake primitive ({@link #requestErasure}) is
 * the F30 forward contract — it accepts the candidate id + an enum reason ONLY, never free text. Admin
 * confirm/reject use a guarded {@code findAndModify} on {@code status == PENDING}, so a double or
 * concurrent decision resolves to a single wipe (the loser gets a 409).
 */
@Service
public class ErasureRequestService {

    private final ErasureRequestRepository requests;
    private final CandidateErasureService erasure;
    private final CandidateAuditService audit;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    public ErasureRequestService(ErasureRequestRepository requests, CandidateErasureService erasure,
                                 CandidateAuditService audit, MongoTemplate mongoTemplate, Clock clock) {
        this.requests = requests;
        this.erasure = erasure;
        this.audit = audit;
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    /**
     * F30-forward intake: PII-free, IDEMPOTENT request creation (FR-022 — no second PENDING for the same
     * candidate). The unique partial {@code {workspaceId,candidateId}} over {@code status:PENDING} index
     * (ChangeUnit015) is the real guard: {@code insert} a fresh PENDING, and on a concurrent/duplicate open
     * request catch {@code DuplicateKeyException} → return the existing PENDING (no second row, no second audit).
     * A fresh PENDING after a prior RESOLVED is permitted by design; the rate-limit bounds churn.
     */
    public ErasureRequest requestErasure(String workspaceId, String candidateId, ErasureReasonCode reason) {
        ErasureRequest r = new ErasureRequest();
        r.setWorkspaceId(workspaceId);
        r.setCandidateId(candidateId);
        r.setStatus(RequestStatus.PENDING);
        r.setReasonCode(reason);
        r.setCreatedAt(Instant.now(clock));
        ErasureRequest saved;
        try {
            saved = requests.insert(r);
        } catch (DuplicateKeyException e) {
            // A PENDING request already exists for this candidate — idempotent: return it, no second audit.
            ErasureRequest existing = requests
                .findFirstByWorkspaceIdAndCandidateIdAndStatus(workspaceId, candidateId, RequestStatus.PENDING)
                .orElse(null);
            if (existing != null) {
                return existing;
            }
            // Extremely narrow race: the existing PENDING was resolved between the clash and this read. Retry once
            // (a fresh PENDING is now permitted); a second clash is then a genuine concurrent insert we re-read.
            try {
                saved = requests.insert(r);
            } catch (DuplicateKeyException e2) {
                return requests
                    .findFirstByWorkspaceIdAndCandidateIdAndStatus(workspaceId, candidateId, RequestStatus.PENDING)
                    .orElseThrow(() -> e2);
            }
        }
        audit.append(workspaceId, candidateId, CandidateEventType.ERASURE_REQUESTED,
            CandidateAuditOutcome.REQUESTED, null);
        return saved;
    }

    public List<ErasureRequest> listPending(String workspaceId) {
        return requests.findByWorkspaceIdAndStatus(workspaceId, RequestStatus.PENDING);
    }

    /** Returns true if confirmed (and the wipe ran); false if the request was not PENDING (-> 409). */
    public boolean confirm(String workspaceId, String requestId, String actorMemberId) {
        ErasureRequest req = transition(workspaceId, requestId, RequestStatus.RESOLVED_CONFIRMED, null, actorMemberId);
        if (req == null) {
            return false;
        }
        audit.append(workspaceId, req.getCandidateId(), CandidateEventType.ERASURE_REQUEST_CONFIRMED,
            CandidateAuditOutcome.CONFIRMED, actorMemberId);
        erasure.wipe(workspaceId, req.getCandidateId(), CandidateAuditOutcome.CANDIDATE_REQUEST, actorMemberId);
        return true;
    }

    /** Returns true if rejected; false if the request was not PENDING (-> 409). */
    public boolean reject(String workspaceId, String requestId, ErasureReasonCode reason, String actorMemberId) {
        ErasureRequest req = transition(workspaceId, requestId, RequestStatus.RESOLVED_REJECTED, reason, actorMemberId);
        if (req == null) {
            return false;
        }
        audit.append(workspaceId, req.getCandidateId(), CandidateEventType.ERASURE_REQUEST_REJECTED,
            CandidateAuditOutcome.REJECTED, actorMemberId);
        return true;
    }

    private ErasureRequest transition(String workspaceId, String requestId, RequestStatus to,
                                      ErasureReasonCode reason, String actorMemberId) {
        Query q = Query.query(Criteria.where("_id").is(requestId)
            .and("workspaceId").is(workspaceId)
            .and("status").is(RequestStatus.PENDING));
        Update u = new Update()
            .set("status", to)
            .set("decidedByMemberId", actorMemberId)
            .set("decidedAt", Instant.now(clock));
        if (reason != null) {
            u.set("reasonCode", reason);
        }
        return mongoTemplate.findAndModify(q, u,
            FindAndModifyOptions.options().returnNew(true), ErasureRequest.class);
    }
}
