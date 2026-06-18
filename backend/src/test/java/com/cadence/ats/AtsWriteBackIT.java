package com.cadence.ats;

import com.cadence.domain.AtsWriteBack;
import com.cadence.domain.AtsWriteBackStatus;
import com.cadence.domain.AtsWriteBackType;
import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.ErasureState;
import com.cadence.domain.RecruiterNotification;
import com.cadence.domain.RecruiterNotificationType;
import com.cadence.service.CandidateErasureService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** F40 US3/US4 outbound write-back: enqueue (linked->1 / non-linked->0), drain delivers one note, idempotent, erased cancel, dead-letter. */
class AtsWriteBackIT extends AtsItBase {

    private static final String WS = "ws-wb";
    private static final Instant EVENT_AT = Instant.parse("2026-07-01T10:00:00Z");

    @Autowired CandidateErasureService erasure;

    private String importCandidate(String ref) {
        connect(WS);
        stub.addCandidate(ref, "Jane", "Roe", ref + "@example.com", "555", "job1", "Engineer", "Phone Screen");
        sync(WS);
        return candidates.findByWorkspaceIdAndAtsProviderAndAtsExternalRef(WS,
            com.cadence.integration.AtsProvider.GREENHOUSE, ref).orElseThrow().getId();
    }

    @Test
    void linkedEnqueuesOneNonLinkedEnqueuesZero() {
        String linked = importCandidate("app1");
        writeBackService.enqueue(WS, linked, AtsWriteBackType.CONFIRMED, EVENT_AT);
        assertThat(writeBacks.findAll()).hasSize(1);

        // A native (non-ATS-linked) candidate -> no write-back row.
        Candidate native_ = new Candidate();
        native_.setWorkspaceId(WS);
        native_.setErasureState(ErasureState.ACTIVE);
        native_.setCreatedAt(Instant.now());
        Candidate saved = candidates.insert(native_);
        writeBackService.enqueue(WS, saved.getId(), AtsWriteBackType.CONFIRMED, EVENT_AT);
        assertThat(writeBacks.findAll()).hasSize(1); // unchanged
    }

    @Test
    void drainDeliversExactlyOneNoteAndIsIdempotent() {
        String id = importCandidate("app1");
        writeBackService.enqueue(WS, id, AtsWriteBackType.CONFIRMED, EVENT_AT);
        writeBackService.enqueue(WS, id, AtsWriteBackType.CONFIRMED, EVENT_AT); // duplicate -> idempotent
        assertThat(writeBacks.findAll()).hasSize(1);

        writeBackScheduler.drain();
        assertThat(stub.notes("app1")).hasSize(1);
        assertThat(writeBacks.findAll().get(0).getStatus()).isEqualTo(AtsWriteBackStatus.DELIVERED);

        writeBackScheduler.drain(); // re-drain -> no second note (already DELIVERED)
        assertThat(stub.notes("app1")).hasSize(1);
    }

    @Test
    void erasureCancelsPendingWriteBacks() {
        String id = importCandidate("app2");
        writeBackService.enqueue(WS, id, AtsWriteBackType.CONFIRMED, EVENT_AT);
        assertThat(writeBacks.findAll().get(0).getStatus()).isEqualTo(AtsWriteBackStatus.PENDING);

        erasure.wipe(WS, id, CandidateAuditOutcome.OPERATOR, "admin1");
        assertThat(writeBacks.findAll().get(0).getStatus()).isEqualTo(AtsWriteBackStatus.CANCELLED);
    }

    @Test
    void degradedConnectionHoldsWriteBackInsteadOfDeadLettering() {
        // Review B1: a transiently-degraded connection (NEEDS_REAUTH) must HOLD pending write-backs, never
        // permanently dead-letter them (SC-004: deliver within 15 min of recovery).
        String id = importCandidate("app4");
        writeBackService.enqueue(WS, id, AtsWriteBackType.CONFIRMED, EVENT_AT);
        mongoTemplate.updateFirst(Query.query(Criteria.where("workspaceId").is(WS)),
            new org.springframework.data.mongodb.core.query.Update()
                .set("status", com.cadence.domain.AtsConnectionStatus.NEEDS_REAUTH),
            com.cadence.domain.AtsConnection.class);
        for (int i = 0; i < 5; i++) {
            writeBackScheduler.drain();
        }
        AtsWriteBack row = writeBacks.findAll().get(0);
        assertThat(row.getStatus()).isEqualTo(AtsWriteBackStatus.PENDING); // held, not DEAD_LETTER
        assertThat(row.getAttemptCount()).isLessThanOrEqualTo(0);          // budget not consumed by the hold

        // Recovery: re-connect and drain -> the held write-back now delivers.
        connect(WS);
        writeBackScheduler.drain();
        assertThat(writeBacks.findAll().get(0).getStatus()).isEqualTo(AtsWriteBackStatus.DELIVERED);
        assertThat(stub.notes("app4")).hasSize(1);
    }

    @Test
    void exhaustedRetriesDeadLetterAndNotify() {
        String id = importCandidate("app3");
        stub.program("POST", "/activity_feed/notes", 503); // persistent failure
        writeBackService.enqueue(WS, id, AtsWriteBackType.CONFIRMED, EVENT_AT);
        for (int i = 0; i < 5; i++) {
            writeBackScheduler.drain(); // backoff is PT0S -> immediately re-due each pass
        }
        assertThat(writeBacks.findAll().get(0).getStatus()).isEqualTo(AtsWriteBackStatus.DEAD_LETTER);
        long alerts = mongoTemplate.count(
            Query.query(Criteria.where("type").is(RecruiterNotificationType.ATS_WRITEBACK_FAILED)),
            RecruiterNotification.class);
        assertThat(alerts).isGreaterThanOrEqualTo(1);
    }
}
