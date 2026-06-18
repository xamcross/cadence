package com.cadence.service;

import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.CandidateEventType;
import com.cadence.domain.ClaimStatus;
import com.cadence.domain.ErasureState;
import com.cadence.domain.InterviewSlotClaim;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.mongodb.client.result.UpdateResult;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

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
    private final SlaDraftInvalidator slaDraftInvalidator;
    private final FeedbackInvalidator feedbackInvalidator;
    private final AtsWriteBackInvalidator atsWriteBackInvalidator;

    public CandidateErasureService(MongoTemplate mongoTemplate, Clock clock, CandidateAuditService audit,
                                   SlaDraftInvalidator slaDraftInvalidator, FeedbackInvalidator feedbackInvalidator,
                                   AtsWriteBackInvalidator atsWriteBackInvalidator) {
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
        this.audit = audit;
        // F31/F32/F40 cycle-break: depend on the NARROW interfaces, not the concrete services (data-model section 9/10).
        this.slaDraftInvalidator = slaDraftInvalidator;
        this.feedbackInvalidator = feedbackInvalidator;
        this.atsWriteBackInvalidator = atsWriteBackInvalidator;
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
            // F22 (research D7 / FR-017): purge the operational deliverability metadata too — no residual
            // bounce state on an erased subject. These are non-converter booleans/instants, so $set/null is safe.
            .set("undeliverable", false)
            .set("undeliverableReason", null)
            .set("undeliverableAt", null)
            .set("undeliverableClearedAt", null)
            // F30 (research D7 / FR-024): clear the candidate-visible status + the status access token atomically
            // within the wipe (no resurrection window). statusToken is converter-managed -> $set null, NEVER
            // $unset (the F03 WorkspaceConfigService.unsetCredential ClassCastException trap; encrypt(null)==null
            // makes $set null correct & null-safe). statusTokenHash is a plain hash -> $unset (removes it from the
            // partial index, so the old status link 404s). The free-text + outcome/instant fields -> $set null.
            .set("statusStage", null)
            .set("statusNextStep", null)
            .set("statusExpectedDate", null)
            .set("statusOutcome", null)
            .set("statusPublishedAt", null)
            .set("statusToken", null)
            .unset("statusTokenHash")
            // F40 (FR-015): clear the imported PII-adjacent fields. atsStageLabel is converter-managed -> $set null
            // (NEVER $unset). atsExternalJobTitle is plain free text -> $set null. RETAIN atsProvider/atsExternalRef/
            // atsExternalJobId — the non-PII reconcile anchor that makes a later poll a guarded no-op (the resolve-
            // then-guarded-write resurrection defense in AtsSyncService), NOT a re-creating insert.
            .set("atsStageLabel", null)
            .set("atsExternalJobTitle", null)
            // F42 (FR-022): clear the CSV-imported content. importStageLabel is converter-managed -> $set null
            // (NEVER $unset — the F03 trap); importRequisitionLabel is plain free text -> $set null. RETAIN
            // origin/importJobId — non-PII provenance, like the ATS reconcile anchor.
            .set("importStageLabel", null)
            .set("importRequisitionLabel", null)
            .set("erasureState", ErasureState.ERASED)
            .set("erasedAt", Instant.now(clock));
        UpdateResult r = mongoTemplate.updateFirst(q, u, Candidate.class);
        if (r.getMatchedCount() == 1) {
            supersedeLiveScheduling(workspaceId, candidateId);
            // F31 (research D8 / FR-021): best-effort invalidate any open SLA draft so an erased subject is not
            // surfaced for an SLA send. The AUTHORITATIVE no-message guard remains the send-time consent gate.
            slaDraftInvalidator.invalidateOpenDraft(workspaceId, candidateId);
            // F32 (research D8 / FR-023): wipe the candidate's scorecard content (PENDING -> INVALIDATED;
            // SUBMITTED -> content nulled, status kept) + drop the token so links 404. Best-effort, no
            // resurrection window: supersedeLiveScheduling already took bookings off BOOKED, so generation
            // cannot re-create a request; the token drop + STATUS-before-TIME resolution are the structural guard.
            feedbackInvalidator.invalidateForCandidate(workspaceId, candidateId);
            // F40 (FR-015): cancel any PENDING ATS write-backs for the erased candidate so no scheduling
            // activity is pushed for them post-erasure. Best-effort; the enqueue path also skips erased candidates.
            atsWriteBackInvalidator.cancelPendingForCandidate(workspaceId, candidateId);
            audit.append(workspaceId, candidateId, CandidateEventType.ERASURE_COMPLETED, reason, actorMemberId);
            return true;
        }
        return false;
    }

    /**
     * F13 (research D10 / FR-014) + F20 (research D9 / FR-024): an erased candidate must carry no live
     * scheduling state and NO calendar event. This runs inside the synchronous {@code wipe()} and does only
     * O(1) writes — supersede any PENDING_SELECTION/BOOKING {@code schedulingRequests} (incl. in-flight
     * reschedule rounds) and release their ACTIVE claims; and for a BOOKED booking, CAS it to CANCELLED,
     * release its claims, {@code $unset} the manage token, and set {@code calendarTeardownPending} so the
     * reaper removes the provider events ASYNC (keeps {@code wipe()} non-blocking — the F04 202 SLA).
     */
    private void supersedeLiveScheduling(String workspaceId, String candidateId) {
        Instant now = Instant.now(clock);

        // (a) Pre-booking + in-flight reschedule rounds -> SUPERSEDED + release claims.
        List<SchedulingRequest> live = mongoTemplate.find(
            Query.query(Criteria.where("workspaceId").is(workspaceId).and("candidateId").is(candidateId)
                .and("status").in(SchedulingStatus.PENDING_SELECTION, SchedulingStatus.BOOKING)),
            SchedulingRequest.class);
        if (!live.isEmpty()) {
            List<String> ids = live.stream().map(SchedulingRequest::getId).toList();
            mongoTemplate.updateMulti(Query.query(Criteria.where("_id").in(ids)),
                new Update().set("status", SchedulingStatus.SUPERSEDED).set("updatedAt", now),
                SchedulingRequest.class);
            mongoTemplate.updateMulti(
                Query.query(Criteria.where("schedulingRequestId").in(ids).and("status").is(ClaimStatus.ACTIVE)),
                new Update().set("status", ClaimStatus.RELEASED), InterviewSlotClaim.class);
        }

        // (b) Active BOOKED interviews -> CANCELLED (O(1)) + release claims + clear manage token + defer the
        // provider event teardown to the reaper (FR-024). $unset the manage token so no usable link survives.
        List<SchedulingRequest> booked = mongoTemplate.find(
            Query.query(Criteria.where("workspaceId").is(workspaceId).and("candidateId").is(candidateId)
                .and("status").is(SchedulingStatus.BOOKED)),
            SchedulingRequest.class);
        if (!booked.isEmpty()) {
            List<String> ids = booked.stream().map(SchedulingRequest::getId).toList();
            // F23 (D9): also $unset the confirm token so no usable confirm link survives for the erased subject.
            // The cascade halts via its status:BOOKED guard; a reminder enqueued in the find->update gap is
            // suppressed by the F22 consent re-gate at send (the authoritative backstop).
            mongoTemplate.updateMulti(Query.query(Criteria.where("_id").in(ids)),
                new Update().set("status", SchedulingStatus.CANCELLED).set("cancelledAt", now)
                    .set("calendarTeardownPending", true).set("updatedAt", now)
                    .unset("manageTokenHash").unset("confirmTokenHash"),
                SchedulingRequest.class);
            mongoTemplate.updateMulti(
                Query.query(Criteria.where("schedulingRequestId").in(ids).and("status").is(ClaimStatus.ACTIVE)),
                new Update().set("status", ClaimStatus.RELEASED), InterviewSlotClaim.class);
        }
    }
}
