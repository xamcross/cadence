package com.cadence.emaildelivery;

import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.DispatchOutcomeReason;
import com.cadence.domain.ErasureState;
import com.cadence.service.CandidateErasureService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T038 (US3, FR-017/SC-002) — F04 erasure purges the F22 deliverability metadata. A candidate flagged
 * undeliverable (hard bounce) then erased has NO residual {@code undeliverable*} state — the gate's
 * operational suppression cannot resurrect on an erased subject, and GDPR stays coherent.
 */
class CandidateErasurePurgeTest extends EmailDeliveryItBase {

    @Autowired CandidateErasureService erasure;

    @Test
    void erasure_resetsUndeliverableMetadata() {
        Candidate c = seedContactableCandidate("c1", "Dana", "dana@example.com");
        // Simulate a prior hard-bounce flip.
        c.setUndeliverable(true);
        c.setUndeliverableReason(DispatchOutcomeReason.HARD_BOUNCE);
        c.setUndeliverableAt(Instant.now(clock));
        c.setUndeliverableClearedAt(Instant.now(clock));
        mongoTemplate.save(c);

        boolean wiped = erasure.wipe(WS, "c1", CandidateAuditOutcome.OPERATOR, "admin1");
        assertThat(wiped).isTrue();

        Candidate after = mongoTemplate.findById("c1", Candidate.class);
        assertThat(after.getErasureState()).isEqualTo(ErasureState.ERASED);
        assertThat(after.isUndeliverable()).isFalse();
        assertThat(after.getUndeliverableReason()).isNull();
        assertThat(after.getUndeliverableAt()).isNull();
        assertThat(after.getUndeliverableClearedAt()).isNull();
    }
}
