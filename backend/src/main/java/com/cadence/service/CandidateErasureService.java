package com.cadence.service;

import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.CandidateEventType;
import com.cadence.domain.ErasureState;
import com.mongodb.client.result.UpdateResult;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * The single shared wipe used by all three erasure paths — operator-triggered (US2),
 * candidate-initiated (US4), and retention-driven (US5) — per FR-006.
 *
 * <p>Guaranteed Art. 17 de-identification: name/email/phone become the marker {@code "[ERASED]"}
 * (stored encrypted by the converter, decrypting back to the marker), and {@code emailHash} is
 * {@code $unset} so no value derived from the former email remains — the subject cannot be
 * re-identified by recomputing the HMAC. Idempotent + race-safe via a guarded single-document
 * {@code updateFirst} on {@code erasureState == ACTIVE} (MongoDB single-doc atomicity makes exactly
 * one concurrent writer match); the {@code ERASURE_COMPLETED} audit is written ONLY by the CAS winner
 * ({@code matchedCount == 1}), so losers / already-erased / missing ids append nothing.
 */
@Service
public class CandidateErasureService {

    public static final String ERASED_MARKER = "[ERASED]";

    private final MongoTemplate mongoTemplate;
    private final Clock clock;
    private final CandidateAuditService audit;

    public CandidateErasureService(MongoTemplate mongoTemplate, Clock clock, CandidateAuditService audit) {
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
        this.audit = audit;
    }

    /**
     * Wipe a candidate's PII. Returns true iff this call performed the wipe (CAS winner); false for an
     * already-erased / missing candidate (benign no-op). Callers return an indistinguishable response
     * regardless, so the endpoint is not an existence oracle.
     */
    public boolean wipe(String workspaceId, String candidateId, CandidateAuditOutcome reason, String actorMemberId) {
        Query q = Query.query(Criteria.where("_id").is(candidateId)
            .and("workspaceId").is(workspaceId)
            .and("erasureState").is(ErasureState.ACTIVE));
        Update u = new Update()
            .set("name", ERASED_MARKER)
            .set("email", ERASED_MARKER)
            .set("phone", ERASED_MARKER)
            .unset("emailHash")   // not converter-managed -> $unset is safe and removes the key entirely
            .set("erasureState", ErasureState.ERASED)
            .set("erasedAt", Instant.now(clock));
        UpdateResult r = mongoTemplate.updateFirst(q, u, Candidate.class);
        if (r.getMatchedCount() == 1) {
            audit.append(workspaceId, candidateId, CandidateEventType.ERASURE_COMPLETED, reason, actorMemberId);
            return true;
        }
        return false;
    }
}
