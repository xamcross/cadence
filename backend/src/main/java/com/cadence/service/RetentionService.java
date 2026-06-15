package com.cadence.service;

import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.CandidateEventType;
import com.cadence.domain.ErasureState;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.repository.CandidateRepository;
import com.cadence.repository.WorkspaceConfigRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Retention enforcement (F04, US5) — consumes the F03-configured retention period (FR-018/FR-020).
 * The scan flags candidates whose age STRICTLY exceeds the period (age basis = {@code lastContactAt},
 * the GDPR last-activity semantics), and CLEARS a stale flag when a record is no longer over-age.
 * Flagging alone never deletes; an Administrator confirms deletion via the shared wipe, guarded so an
 * unflagged ACTIVE candidate is never wiped by this path.
 */
@Service
public class RetentionService {

    private final CandidateRepository candidates;
    private final WorkspaceConfigRepository configs;
    private final CandidateErasureService erasure;
    private final CandidateAuditService audit;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    public RetentionService(CandidateRepository candidates, WorkspaceConfigRepository configs,
                            CandidateErasureService erasure, CandidateAuditService audit,
                            MongoTemplate mongoTemplate, Clock clock) {
        this.candidates = candidates;
        this.configs = configs;
        this.erasure = erasure;
        this.audit = audit;
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    /** Flag over-age active candidates and clear flags that are no longer over-age. Idempotent. */
    public void scan(String workspaceId) {
        WorkspaceConfig cfg = configs.findByWorkspaceId(workspaceId).orElse(null);
        if (cfg == null || !cfg.isConfigured() || cfg.getRetentionPeriodDays() <= 0) {
            return; // no binding retention policy yet
        }
        Instant threshold = Instant.now(clock).minus(Duration.ofDays(cfg.getRetentionPeriodDays()));

        // Flag: strictly over-age (lastContactAt < threshold), active, not already flagged.
        List<Candidate> overAge = candidates.findByWorkspaceIdAndErasureStateAndLastContactAtBefore(
            workspaceId, ErasureState.ACTIVE, threshold);
        for (Candidate c : overAge) {
            if (!c.isRetentionFlagged()) {
                setFlag(workspaceId, c.getId(), true);
            }
        }

        // Clear: flagged but no longer over-age (period lengthened; the activity-refresh path that
        // moves lastContactAt forward is a forward concern of F13/F22 and is dormant in F04).
        List<Candidate> flagged = candidates.findByWorkspaceIdAndRetentionFlaggedTrueAndErasureState(
            workspaceId, ErasureState.ACTIVE);
        for (Candidate c : flagged) {
            Instant lca = c.getLastContactAt();
            if (lca == null || !lca.isBefore(threshold)) {
                setFlag(workspaceId, c.getId(), false);
            }
        }
    }

    private void setFlag(String workspaceId, String candidateId, boolean flagged) {
        Query q = Query.query(Criteria.where("_id").is(candidateId).and("workspaceId").is(workspaceId));
        Update u = new Update()
            .set("retentionFlagged", flagged)
            .set("retentionFlaggedAt", flagged ? Instant.now(clock) : null);
        mongoTemplate.updateFirst(q, u, Candidate.class);
        audit.append(workspaceId, candidateId,
            flagged ? CandidateEventType.RETENTION_FLAGGED : CandidateEventType.RETENTION_FLAG_CLEARED,
            flagged ? CandidateAuditOutcome.FLAGGED : CandidateAuditOutcome.CLEARED, null);
    }

    public List<Candidate> listFlagged(String workspaceId) {
        return candidates.findByWorkspaceIdAndRetentionFlaggedTrueAndErasureState(workspaceId, ErasureState.ACTIVE);
    }

    /**
     * Admin-confirmed deletion. Wipes ONLY a currently-flagged, active candidate (never an unflagged
     * one); a not-flagged/unknown id is a benign no-op returning the same shape (no oracle).
     */
    public boolean confirmDelete(String workspaceId, String candidateId, String actorMemberId) {
        Candidate c = candidates.findByWorkspaceIdAndId(workspaceId, candidateId).orElse(null);
        if (c == null || !c.isRetentionFlagged() || c.getErasureState() != ErasureState.ACTIVE) {
            return false;
        }
        boolean wiped = erasure.wipe(workspaceId, candidateId, CandidateAuditOutcome.RETENTION, actorMemberId);
        if (wiped) {
            audit.append(workspaceId, candidateId, CandidateEventType.RETENTION_DELETED,
                CandidateAuditOutcome.DELETED, actorMemberId);
        }
        return wiped;
    }
}
