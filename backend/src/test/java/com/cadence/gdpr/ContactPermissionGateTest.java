package com.cadence.gdpr;

import com.cadence.domain.Candidate;
import com.cadence.domain.ErasureState;
import com.cadence.domain.LawfulBasis;
import com.cadence.repository.CandidateRepository;
import com.cadence.service.ContactPermissionGate;
import com.cadence.service.ContactPermissionGate.Reason;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T024 / SC-001: the contact-permission gate truth table. Pure Mockito unit over a mocked repository
 * (EXEMPT from the seed-via-create rule) — hand-builds every candidate state, including overlapping
 * deny states (to assert precedence) and the fail-closed branches (missing / read-error).
 */
class ContactPermissionGateTest {

    private final CandidateRepository repo = mock(CandidateRepository.class);
    private final ContactPermissionGate gate = new ContactPermissionGate(repo);

    private void stub(Candidate c) {
        when(repo.findByWorkspaceIdAndId("ws1", "c1")).thenReturn(Optional.ofNullable(c));
    }

    private static Candidate cand(ErasureState es, boolean flagged, boolean withdrawn, LawfulBasis basis) {
        Candidate c = new Candidate();
        c.setErasureState(es);
        c.setRetentionFlagged(flagged);
        c.setBasisWithdrawn(withdrawn);
        c.setLawfulBasis(basis);
        return c;
    }

    private ContactPermissionGate.Decision evaluate() {
        return gate.evaluate("ws1", "c1");
    }

    @Test
    void permits_onlyWhenActive_notFlagged_basisSet_notWithdrawn() {
        stub(cand(ErasureState.ACTIVE, false, false, LawfulBasis.CONSENT));
        assertThat(evaluate().permit()).isTrue();
    }

    @Test
    void denies_noBasis_whenBasisNull() {
        stub(cand(ErasureState.ACTIVE, false, false, null));
        assertThat(evaluate()).isEqualTo(new ContactPermissionGate.Decision(false, Reason.NO_BASIS));
    }

    @Test
    void denies_withdrawn_overNoBasisAbsenceIrrelevant() {
        stub(cand(ErasureState.ACTIVE, false, true, LawfulBasis.CONSENT));
        assertThat(evaluate().reason()).isEqualTo(Reason.WITHDRAWN);
    }

    @Test
    void denies_overRetention_whenFlagged_evenWithBasis() {
        stub(cand(ErasureState.ACTIVE, true, false, LawfulBasis.CONSENT));
        assertThat(evaluate().reason()).isEqualTo(Reason.OVER_RETENTION);
    }

    @Test
    void denies_erased_takesPrecedenceOverAll() {
        // erased + flagged + withdrawn + no basis -> precedence picks ERASED
        stub(cand(ErasureState.ERASED, true, true, null));
        assertThat(evaluate().reason()).isEqualTo(Reason.ERASED);
    }

    @Test
    void precedence_overRetention_beatsWithdrawn_andNoBasis() {
        stub(cand(ErasureState.ACTIVE, true, true, null));
        assertThat(evaluate().reason()).isEqualTo(Reason.OVER_RETENTION);
    }

    @Test
    void failsClosed_whenCandidateMissing() {
        stub(null);
        ContactPermissionGate.Decision d = evaluate();
        assertThat(d.permit()).isFalse();
        assertThat(d.reason()).isEqualTo(Reason.UNAVAILABLE);
    }

    @Test
    void failsClosed_onReadError() {
        when(repo.findByWorkspaceIdAndId("ws1", "c1")).thenThrow(new RuntimeException("db down"));
        ContactPermissionGate.Decision d = evaluate();
        assertThat(d.permit()).isFalse();
        assertThat(d.reason()).isEqualTo(Reason.UNAVAILABLE);
    }
}
