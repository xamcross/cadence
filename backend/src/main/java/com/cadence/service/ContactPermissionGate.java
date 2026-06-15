package com.cadence.service;

import com.cadence.domain.Candidate;
import com.cadence.domain.ErasureState;
import com.cadence.repository.CandidateRepository;
import org.springframework.stereotype.Service;

/**
 * The single decision point for whether a candidate may be contacted by email (F04, FR-004). F22's
 * EmailSender MUST consult this before every dispatch (forward contract).
 *
 * <p><b>Positive evaluation</b>: {@code permit} is returned ONLY on the explicitly-good state
 * (ACTIVE, not flagged, basis recorded, not withdrawn). Any other, missing, or unreadable state
 * denies — so a future/corrupt value can never fall through to permit (fail-closed). When several
 * deny conditions hold the reason follows precedence {@code erased > over_retention > withdrawn >
 * no_basis}.
 */
@Service
public class ContactPermissionGate {

    public enum Reason { NONE, NO_BASIS, WITHDRAWN, ERASED, OVER_RETENTION, UNAVAILABLE }

    public record Decision(boolean permit, Reason reason) {
        public static Decision allow() { return new Decision(true, Reason.NONE); }
        public static Decision deny(Reason r) { return new Decision(false, r); }
    }

    private final CandidateRepository candidates;

    public ContactPermissionGate(CandidateRepository candidates) {
        this.candidates = candidates;
    }

    public Decision evaluate(String workspaceId, String candidateId) {
        try {
            Candidate c = candidates.findByWorkspaceIdAndId(workspaceId, candidateId).orElse(null);
            if (c == null) {
                return Decision.deny(Reason.UNAVAILABLE);
            }
            // Precedence, evaluated positively (permit only on the explicit-good row below).
            if (c.getErasureState() != ErasureState.ACTIVE) {
                return Decision.deny(Reason.ERASED);
            }
            if (c.isRetentionFlagged()) {
                return Decision.deny(Reason.OVER_RETENTION);
            }
            if (c.isBasisWithdrawn()) {
                return Decision.deny(Reason.WITHDRAWN);
            }
            if (c.getLawfulBasis() == null) {
                return Decision.deny(Reason.NO_BASIS);
            }
            return Decision.allow();
        } catch (RuntimeException e) {
            // Fail closed on any read/error — never permit a candidate whose state we cannot confirm.
            return Decision.deny(Reason.UNAVAILABLE);
        }
    }
}
