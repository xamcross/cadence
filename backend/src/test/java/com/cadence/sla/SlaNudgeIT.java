package com.cadence.sla;

import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditEvent;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.CandidateEventType;
import com.cadence.domain.CandidateStatusOutcome;
import com.cadence.domain.RecruiterNotification;
import com.cadence.domain.RecruiterNotificationType;
import com.cadence.domain.Role;
import com.cadence.domain.SlaDraftStatus;
import com.cadence.domain.SlaNudgeDraft;
import com.cadence.domain.SlaState;
import com.cadence.scheduler.SlaNudgeScheduler;
import com.cadence.service.CandidateErasureService;
import com.cadence.service.SlaNudgeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F31 T024-T028 integration coverage: scan de-dup (SC-003), suppression (SC-005) + terminal guardrail (SC-012),
 * approve sends one + advances lastContactAt + clears breach + audits (SC-004/SC-011/SC-014 site 5), dismiss sends
 * zero, missed-fire-proxy double sweep (SC-006), erasure invalidates the open draft + no send (SC-015).
 */
class SlaNudgeIT extends SlaItBase {

    @Autowired SlaNudgeService sla;
    @Autowired SlaNudgeScheduler scheduler;
    @Autowired CandidateErasureService erasure;

    private SlaNudgeDraft openDraft(String candidateId) {
        return mongoTemplate.findOne(Query.query(Criteria.where("candidateId").is(candidateId)
            .and("status").is(SlaDraftStatus.OPEN)), SlaNudgeDraft.class);
    }

    @Test
    void scan_breaching_createsExactlyOneDraftAndNotification_idempotentOnRepeatSweep() {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test", 10); // 10 days > 5-day window -> RED

        scheduler.sweep();
        scheduler.sweep(); // SC-006 proxy: a second/replay sweep must not create a second draft
        scheduler.sweep();

        assertThat(openDraftCount()).isEqualTo(1);
        long notifs = mongoTemplate.count(Query.query(Criteria.where("candidateId").is("c1")
            .and("type").is(RecruiterNotificationType.SLA_DRAFT_PENDING)), RecruiterNotification.class);
        assertThat(notifs).isEqualTo(1);
        assertThat(emailDispatchCount()).isZero(); // the scan NEVER sends (FR-010/SC-008)
    }

    @Test
    void scan_suppressesErasedTerminalUndeliverableAndWithdrawn() {
        configuredWorkspace();
        // Erased candidate (gate denies) past the window.
        Candidate erased = seedCandidate("c1", "Ada", "ada@x.test", 10);
        erased.setErasureState(com.cadence.domain.ErasureState.ERASED);
        mongoTemplate.save(erased);
        // Terminal-outcome candidate past the window.
        Candidate term = seedCandidate("c2", "Ben", "ben@x.test", 10);
        term.setStatusOutcome(CandidateStatusOutcome.COMPLETE_REJECTED);
        mongoTemplate.save(term);
        // Undeliverable (hard-bounce) candidate past the window.
        Candidate undeliverable = seedCandidate("c3", "Cy", "cy@x.test", 10);
        undeliverable.setUndeliverable(true);
        mongoTemplate.save(undeliverable);
        // Consent-withdrawn candidate past the window.
        Candidate withdrawn = seedCandidate("c4", "Di", "di@x.test", 10);
        withdrawn.setBasisWithdrawn(true);
        mongoTemplate.save(withdrawn);

        scheduler.sweep();

        assertThat(openDraftCount()).isZero(); // SC-005 + SC-012: every suppressed state blocks drafting
    }

    @Test
    void concurrentApprove_yieldsAtMostOneDispatch() throws Exception {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test", 10);
        var rec = member("rec@x.test", Role.RECRUITER);
        scheduler.sweep();
        String draftId = openDraft("c1").getId();

        int threads = 4;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var start = new java.util.concurrent.CountDownLatch(1);
        var results = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(sla.approve(WS, draftId, rec.getId()).result());
                } catch (Exception e) {
                    results.add("ERR:" + e.getClass().getSimpleName());
                }
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS);

        long enqueued = results.stream().filter("SENT_ENQUEUED"::equals).count();
        assertThat(enqueued).isEqualTo(1); // exactly one winner (the draft CAS is the primary guard, SC-010)
        assertThat(emailDispatchCount()).isEqualTo(1);
    }

    @Test
    void approve_sendsOne_advancesLastContact_clearsBreach_audits() {
        configuredWorkspace();
        Candidate c = seedCandidate("c1", "Ada", "ada@x.test", 10);
        var rec = member("rec@x.test", Role.RECRUITER);
        scheduler.sweep();
        SlaNudgeDraft draft = openDraft("c1");
        assertThat(draft).isNotNull();

        var result = sla.approve(WS, draft.getId(), rec.getId());

        assertThat(result.result()).isEqualTo("SENT_ENQUEUED");
        assertThat(emailDispatchCount()).isEqualTo(1); // exactly one SLA_HOLDING enqueued
        // lastContactAt advanced -> breach cleared (SC-014 site 5, asserted independently of the actual send).
        Candidate after = mongoTemplate.findById("c1", Candidate.class);
        assertThat(after.getLastContactAt()).isAfter(c.getLastContactAt());
        assertThat(sla.candidateSla(WS, "c1").slaState()).isEqualTo(SlaState.GREEN);
        // audited SLA_DRAFT_APPROVED.
        long audits = mongoTemplate.count(Query.query(Criteria.where("candidateId").is("c1")
            .and("eventType").is(CandidateEventType.SLA_DRAFT_APPROVED)), CandidateAuditEvent.class);
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void dismiss_sendsNothing_audits_andIsReDraftable() {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test", 10);
        var rec = member("rec@x.test", Role.RECRUITER);
        scheduler.sweep();
        SlaNudgeDraft draft = openDraft("c1");

        var result = sla.dismiss(WS, draft.getId(), rec.getId());

        assertThat(result.result()).isEqualTo("DISMISSED");
        assertThat(emailDispatchCount()).isZero();
        assertThat(openDraftCount()).isZero();
        long audits = mongoTemplate.count(Query.query(Criteria.where("candidateId").is("c1")
            .and("eventType").is(CandidateEventType.SLA_DRAFT_DISMISSED)), CandidateAuditEvent.class);
        assertThat(audits).isEqualTo(1);
        // Still silent -> a future sweep re-drafts.
        scheduler.sweep();
        assertThat(openDraftCount()).isEqualTo(1);
    }

    @Test
    void approveTwice_secondIsAlreadyActioned_singleSend() {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test", 10);
        var rec = member("rec@x.test", Role.RECRUITER);
        scheduler.sweep();
        SlaNudgeDraft draft = openDraft("c1");

        var first = sla.approve(WS, draft.getId(), rec.getId());
        var second = sla.approve(WS, draft.getId(), rec.getId());

        assertThat(first.result()).isEqualTo("SENT_ENQUEUED");
        assertThat(second.result()).isEqualTo("ALREADY_ACTIONED");
        assertThat(emailDispatchCount()).isEqualTo(1);
    }

    @Test
    void erasure_invalidatesOpenDraft_andApproveSendsNothing() {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test", 10);
        var rec = member("rec@x.test", Role.RECRUITER);
        scheduler.sweep();
        SlaNudgeDraft draft = openDraft("c1");

        boolean wiped = erasure.wipe(WS, "c1", CandidateAuditOutcome.OPERATOR, rec.getId());
        assertThat(wiped).isTrue();

        // SC-015 first half: the open draft is invalidated as part of the wipe.
        SlaNudgeDraft after = mongoTemplate.findById(draft.getId(), SlaNudgeDraft.class);
        assertThat(after.getStatus()).isEqualTo(SlaDraftStatus.INVALIDATED);
        // SC-015 second half: approving the (now non-OPEN) draft sends nothing.
        var result = sla.approve(WS, draft.getId(), rec.getId());
        assertThat(result.result()).isEqualTo("ALREADY_ACTIONED");
        assertThat(emailDispatchCount()).isZero();
    }
}
