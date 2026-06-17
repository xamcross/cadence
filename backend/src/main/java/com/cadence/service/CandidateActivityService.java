package com.cadence.service;

import com.cadence.domain.Candidate;
import com.cadence.domain.ErasureState;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * F31 (research D1) — the single home of the canonical "last meaningful activity" write. The spec's
 * last-meaningful-activity instant (FR-005) is realised by advancing the existing
 * {@code Candidate.lastContactAt} (its documented-but-dormant "activity-refresh" forward intent) at the
 * qualifying write sites: an outbound candidate email SENT (F22), a status publish (F30), an interview
 * booked/rescheduled (F13/F20), and an SLA-draft approval (F31). No new field/index/backfill -- the
 * {@code {workspaceId,lastContactAt}} index (ChangeUnit001) already backs the breach scan.
 *
 * <p>Value-free + ACTIVE-guarded: an erased candidate's instant is never moved. Idempotent. No
 * candidate-originated path calls this (FR-005) -- only system/recruiter-confirmed activity.
 */
@Service
public class CandidateActivityService {

    private final MongoTemplate mongo;

    public CandidateActivityService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** Advance lastContactAt to {@code now} for an ACTIVE candidate (no-op if missing/erased). */
    public void advanceLastContact(String workspaceId, String candidateId, Instant now) {
        if (workspaceId == null || candidateId == null) {
            return;
        }
        mongo.updateFirst(
            Query.query(Criteria.where("_id").is(candidateId).and("workspaceId").is(workspaceId)
                .and("erasureState").is(ErasureState.ACTIVE)),
            new Update().set("lastContactAt", now),
            Candidate.class);
    }
}
