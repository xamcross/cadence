package com.cadence.service;

/**
 * F32 narrow seam for candidate erasure to wipe a candidate's scorecards + invalidate pending requests
 * (data-model section 10). The cycle-break (the {@code SlaDraftInvalidator} precedent): {@code CandidateErasureService}
 * depends on THIS interface, NOT the concrete {@code FeedbackService}, so the erasure wipe edge cannot pull a
 * status/erasure service back into the constructor graph. (F32's {@code FeedbackService} injects no
 * status/erasure service, so no cycle exists today — but the narrow interface is the house style.)
 */
public interface FeedbackInvalidator {

    /**
     * Best-effort, as part of the erasure wipe: clear the encrypted scorecard content of EVERY feedback row for
     * the candidate (pending -> INVALIDATED; submitted -> content nulled, status kept), drop the token so the
     * link 404s, and audit the invalidation (FR-023/SC-013).
     */
    void invalidateForCandidate(String workspaceId, String candidateId);
}
