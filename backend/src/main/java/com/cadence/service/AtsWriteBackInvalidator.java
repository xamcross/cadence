package com.cadence.service;

import com.cadence.integration.AtsProvider;

/**
 * Narrow seam (F40/F41) letting {@link CandidateErasureService} (on erasure) and {@code AtsConnectionService}
 * (on disconnect) cancel pending ATS write-backs WITHOUT depending on the concrete {@code AtsWriteBackService}
 * — the F31 {@code SlaDraftInvalidator} / F32 {@code FeedbackInvalidator} cycle-break precedent. Implemented by
 * {@code AtsWriteBackService}. All methods are best-effort no-ops if no rows match.
 */
public interface AtsWriteBackInvalidator {

    /** Cancel all PENDING write-backs for one candidate (erasure — FR-015; provider-agnostic — a candidate has one provider). */
    void cancelPendingForCandidate(String workspaceId, String candidateId);

    /** Cancel all PENDING write-backs for a whole workspace (legacy F40 path). */
    void cancelPendingForWorkspace(String workspaceId);

    /** Cancel all PENDING write-backs for one (workspace, provider) — F41 provider-scoped disconnect (FR-005/SC-015). */
    void cancelPendingForWorkspaceAndProvider(String workspaceId, AtsProvider provider);
}
