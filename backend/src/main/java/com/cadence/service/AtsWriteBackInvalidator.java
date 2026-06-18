package com.cadence.service;

/**
 * Narrow seam (F40) letting {@link CandidateErasureService} (on erasure) and {@code AtsConnectionService}
 * (on disconnect) cancel pending ATS write-backs WITHOUT depending on the concrete {@code AtsWriteBackService}
 * — the F31 {@code SlaDraftInvalidator} / F32 {@code FeedbackInvalidator} cycle-break precedent. Implemented by
 * {@code AtsWriteBackService}. Both methods are best-effort no-ops if no rows match.
 */
public interface AtsWriteBackInvalidator {

    /** Cancel all PENDING write-backs for one candidate (erasure — FR-015). */
    void cancelPendingForCandidate(String workspaceId, String candidateId);

    /** Cancel all PENDING write-backs for a whole workspace (disconnect — FR-005). */
    void cancelPendingForWorkspace(String workspaceId);
}
