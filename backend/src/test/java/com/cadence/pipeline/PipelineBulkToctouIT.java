package com.cadence.pipeline;

import com.cadence.domain.Candidate;
import com.cadence.domain.DispatchOutcomeReason;
import com.cadence.domain.DispatchStatus;
import com.cadence.domain.EmailDispatch;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.ErasureState;
import com.cadence.service.ContactPermissionGate;
import com.cadence.service.EmailDispatchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F51 T037 / SC-006 (authoritative TOCTOU backstop): the bulk synchronous {@link ContactPermissionGate} pre-check
 * is best-effort feedback only — the GUARANTEE that an erased candidate receives zero messages rests on the
 * asynchronous send-time gate re-evaluated at the outbox claim (inside {@link EmailDispatchService#dispatch}).
 *
 * <p>This models the TOCTOU window the bulk inline path cannot expose single-threadedly: the candidate passes the
 * synchronous pre-check, the fan-out enqueues the send (here deferred via a future {@code scheduledFor} so the row
 * sits PENDING), the candidate is THEN erased, and only afterwards does the worker claim the row. The claim-time
 * gate must fail closed: row REFUSED/ERASED, zero sends, candidate not resurrected.
 */
class PipelineBulkToctouIT extends PipelineItBase {

    @Autowired EmailDispatchService dispatch;
    @Autowired ContactPermissionGate gate;

    @Test
    void candidatePassesSyncPrecheck_thenErasedBeforeClaim_sendTimeGateFailsClosed() {
        configuredWorkspace();
        Candidate c = seedActive("c1", "Ada", 1, null);   // contactable (CONSENT, ACTIVE)

        // 1) The synchronous bulk pre-check passes for the contactable candidate.
        assertThat(gate.evaluate(WS, "c1").permit()).isTrue();

        // 2) The fan-out enqueues the update email — deferred so the row stays PENDING (the TOCTOU window).
        Instant future = NOW.plus(Duration.ofMinutes(10));
        EmailDispatchService.DispatchResult enq =
            dispatch.enqueue(WS, "c1", EmailMessageType.HOLD_UPDATE, "BASE", future, Map.of(), "c1");
        assertThat(enq.status()).isEqualTo(DispatchStatus.PENDING);

        // 3) The candidate is erased AFTER the pre-check, BEFORE the outbox claim.
        c.setErasureState(ErasureState.ERASED);
        mongoTemplate.save(c);

        // 4) The worker claims at send time -> the gate is re-evaluated and fails closed.
        clock.set(future.plus(Duration.ofSeconds(1)));
        EmailDispatchService.DispatchResult ran = dispatch.dispatch(enq.dispatchId(), Map.of());

        // SC-006: REFUSED at the authoritative send-time gate, with the erasure cause.
        assertThat(ran.status()).isEqualTo(DispatchStatus.REFUSED);
        assertThat(ran.reason()).isEqualTo(DispatchOutcomeReason.ERASED);

        EmailDispatch row = mongoTemplate.findById(enq.dispatchId(), EmailDispatch.class);
        assertThat(row).isNotNull();
        assertThat(row.getStatus()).isEqualTo(DispatchStatus.REFUSED);

        // Zero sends: no row ever reached SENT.
        long sent = mongoTemplate.count(
            Query.query(Criteria.where("status").is(DispatchStatus.SENT)), EmailDispatch.class);
        assertThat(sent).isZero();

        // Not resurrected: the dispatch path wrote no candidate PII / state — the candidate stays ERASED.
        Candidate after = mongoTemplate.findById("c1", Candidate.class);
        assertThat(after).isNotNull();
        assertThat(after.getErasureState()).isEqualTo(ErasureState.ERASED);
    }
}
