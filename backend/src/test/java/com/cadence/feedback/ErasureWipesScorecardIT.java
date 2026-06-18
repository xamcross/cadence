package com.cadence.feedback;

import com.cadence.domain.CandidateAuditEvent;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.CandidateEventType;
import com.cadence.domain.FeedbackRequest;
import com.cadence.domain.FeedbackRequestStatus;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.service.CandidateErasureService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-013 (the review BLOCKER): candidate erasure wipes the scorecard CONTENT of EVERY row — pending (-&gt;
 * INVALIDATED) AND submitted (content nulled, status kept) — drops the token (link dies), and audits
 * FEEDBACK_INVALIDATED.
 */
class ErasureWipesScorecardIT extends FeedbackItBase {

    private static final String SENTINEL = "SENTINELF32TEXTzz9";

    @Autowired CandidateErasureService erasure;

    @Test
    void erasure_wipesSubmittedAndPendingScorecards_dropsToken_audits() {
        configuredWorkspace();
        seedCandidate("cand1", "Dana Doe", "dana@example.com");
        Member a = member("intA@example.com", Role.INTERVIEWER);
        Member b = member("intB@example.com", Role.INTERVIEWER);
        seedBookedInterview("req1", "cand1", 4);
        seedClaim("req1", a.getId(), Instant.now(clock).minus(Duration.ofHours(4)));
        seedClaim("req1", b.getId(), Instant.now(clock).minus(Duration.ofHours(4)));
        scheduler.sweep();

        // Submit interviewer A's scorecard (SUBMITTED); leave B's PENDING.
        FeedbackRequest aReq = feedbackRepo.findByWorkspaceIdAndInterviewEventId(WS, "req1").stream()
            .filter(r -> r.getInterviewerMemberId().equals(a.getId())).findFirst().orElseThrow();
        feedbackService.submit(aReq.getToken(),
            new com.cadence.api.FeedbackDtos.ScorecardSubmission("YES", java.util.List.of(), SENTINEL), "1.1.1.1");

        // Erase the candidate.
        boolean wiped = erasure.wipe(WS, "cand1", CandidateAuditOutcome.RECORDED, "admin1");
        assertThat(wiped).isTrue();

        // Every feedbackRequests doc for the candidate has NO scorecard content and NO token (raw-driver read).
        for (Document raw : mongoTemplate.getCollection("feedbackRequests").find()) {
            assertThat(raw.getString("scorecardPayload")).isNull();
            assertThat(raw.getString("token")).isNull();
            assertThat(raw.getString("tokenHash")).isNull(); // dropped -> link 404s
        }
        // Statuses: A kept SUBMITTED (the "who responded" trail), B -> INVALIDATED.
        FeedbackRequest aAfter = feedbackRepo.findByWorkspaceIdAndInterviewEventId(WS, "req1").stream()
            .filter(r -> r.getInterviewerMemberId().equals(a.getId())).findFirst().orElseThrow();
        FeedbackRequest bAfter = feedbackRepo.findByWorkspaceIdAndInterviewEventId(WS, "req1").stream()
            .filter(r -> r.getInterviewerMemberId().equals(b.getId())).findFirst().orElseThrow();
        assertThat(aAfter.getStatus()).isEqualTo(FeedbackRequestStatus.SUBMITTED);
        assertThat(aAfter.getScorecardPayload()).isNull();
        assertThat(bAfter.getStatus()).isEqualTo(FeedbackRequestStatus.INVALIDATED);

        // SC-018: an invalidation audit was written.
        long invalidated = mongoTemplate.count(
            Query.query(Criteria.where("candidateId").is("cand1")
                .and("eventType").is(CandidateEventType.FEEDBACK_INVALIDATED)),
            CandidateAuditEvent.class);
        assertThat(invalidated).isEqualTo(1);
    }
}
