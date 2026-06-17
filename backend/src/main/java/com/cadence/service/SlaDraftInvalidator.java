package com.cadence.service;

/**
 * F31 narrow seam for erasure to invalidate a candidate's open SLA draft (data-model section 9). The
 * cycle-break: {@code CandidateErasureService} depends on THIS interface, NOT the concrete
 * {@code SlaNudgeService} -- otherwise the constructor graph closes
 * {@code CandidateErasureService -> SlaNudgeService -> CandidateStatusService -> ErasureRequestService ->
 * CandidateErasureService} and Spring fails startup with BeanCurrentlyInCreationException. (The
 * {@code SlaNudgeService} side additionally injects {@code CandidateStatusService} lazily as a second,
 * independent break.)
 */
public interface SlaDraftInvalidator {

    /** Best-effort CAS OPEN -> INVALIDATED for any open SLA draft of the candidate. */
    void invalidateOpenDraft(String workspaceId, String candidateId);
}
